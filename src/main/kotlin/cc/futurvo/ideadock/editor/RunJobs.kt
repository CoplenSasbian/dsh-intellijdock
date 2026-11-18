package cc.futurvo.ideadock.editor

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The run/terminal/debug jobs the MCP tools start.
 *
 * Starting a process and waiting for it to finish is the wrong shape for an
 * agent tool: a server or a GUI app never exits, and a slow build holds the
 * whole conversation. The tools start a job, return its id at once, and the
 * agent polls [status] / [output] (or stops it) while the IDE keeps showing the
 * console in the Run tool window.
 *
 * Output is kept twice: a bounded tail in memory for a quick status answer, and
 * the full transcript in a temp file whose path is returned.
 */
object RunJobs {

    private val log = Logger.getInstance(RunJobs::class.java)
    private val counter = AtomicLong()
    private val jobs = ConcurrentHashMap<String, Job>()

    /** One started process. */
    class Job internal constructor(
        val id: String,
        val name: String,
        val kind: String,
        val startedAt: Long = System.currentTimeMillis()
    ) {
        @Volatile
        var handler: ProcessHandler? = null

        @Volatile
        var state: String = "starting"

        @Volatile
        var exitCode: Int? = null

        @Volatile
        var terminatedAt: Long? = null

        @Volatile
        var outputPath: String? = null

        @Volatile
        var error: String? = null

        internal val output = StringBuilder()
        internal var writer: BufferedWriter? = null
    }

    fun create(name: String, kind: String): Job {
        val id = kind + "-" + counter.incrementAndGet()
        val job = Job(id, name, kind)
        job.outputPath = tempFile(id)
        jobs[id] = job
        if (jobs.size > MAX_JOBS) {
            jobs.values
                .filter { it.state == "finished" || it.state == "failed" }
                .sortedBy { it.startedAt }
                .take(jobs.size - MAX_JOBS)
                .forEach { jobs.remove(it.id) }
        }
        return job
    }

    /** Attach the process the IDE started; called once. */
    fun attach(job: Job, handler: ProcessHandler) {
        if (job.handler != null) {
            return
        }
        job.handler = handler
        job.state = "running"
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                append(job, event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                job.state = "finished"
                job.exitCode = event.exitCode
                job.terminatedAt = System.currentTimeMillis()
                closeWriter(job)
            }
        })
        if (handler.isProcessTerminated) {
            job.state = "finished"
            job.exitCode = handler.exitCode
            job.terminatedAt = System.currentTimeMillis()
            closeWriter(job)
        }
    }

    fun fail(job: Job, message: String) {
        job.state = "failed"
        job.error = message
        job.terminatedAt = System.currentTimeMillis()
        closeWriter(job)
    }

    fun startFailed(job: Job, message: String): Map<String, Any?> {
        fail(job, message)
        return status(job, 4000)
    }

    /** The answer returned as soon as the process is launched. */
    fun started(job: Job): Map<String, Any?> = linkedMapOf(
        "id" to job.id,
        "name" to job.name,
        "kind" to job.kind,
        "status" to job.state,
        "startedAt" to job.startedAt,
        "outputPath" to job.outputPath,
        "hint" to "poll job_status with this id; the process continues in the IDE's Run tool window"
    )

    fun status(job: Job, maxChars: Int): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val output = synchronized(job) {
            val text = job.output.toString()
            if (text.length <= maxChars) text else text.substring(text.length - maxChars)
        }
        return linkedMapOf(
            "id" to job.id,
            "name" to job.name,
            "kind" to job.kind,
            "status" to job.state,
            "running" to (job.state == "running" || job.state == "starting"),
            "exitCode" to job.exitCode,
            "startedAt" to job.startedAt,
            "durationMs" to (job.terminatedAt ?: now) - job.startedAt,
            "output" to output,
            "truncated" to (job.output.length > maxChars),
            "outputPath" to job.outputPath,
            "error" to job.error
        )
    }

    fun output(job: Job, offset: Int, maxChars: Int): Map<String, Any?> {
        val text = synchronized(job) { job.output.toString() }
        val from = offset.coerceIn(0, text.length)
        val to = (from + maxChars).coerceAtMost(text.length)
        return linkedMapOf(
            "id" to job.id,
            "offset" to from,
            "nextOffset" to to,
            "text" to text.substring(from, to),
            "truncated" to (to < text.length)
        )
    }

    fun find(id: String): Job? = jobs[id]

    /**
     * Wait (at most [timeoutMs]) for the job to leave the running state, then
     * answer with its status. Zero answers immediately.
     */
    fun waitFor(job: Job, timeoutMs: Int, maxChars: Int): Map<String, Any?> {
        val budget = timeoutMs.coerceIn(0, 60_000)
        val deadline = System.currentTimeMillis() + budget
        while (budget > 0 && isRunning(job) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return status(job, maxChars)
    }

    private fun isRunning(job: Job): Boolean = job.state == "running" || job.state == "starting"

    fun stop(job: Job): Boolean {
        val handler = job.handler ?: return false
        if (handler.isProcessTerminated) {
            return false
        }
        handler.destroyProcess()
        job.state = "stopping"
        return true
    }

    fun all(): List<Job> = jobs.values.sortedBy { it.startedAt }

    /* ------------------------------- internals ------------------------------ */

    private fun append(job: Job, text: String) {
        if (text.isEmpty()) {
            return
        }
        synchronized(job) {
            job.output.append(text)
            if (job.output.length > MAX_MEMORY) {
                job.output.delete(0, job.output.length - MAX_MEMORY)
            }
            try {
                val writer = job.writer ?: openWriter(job).also { job.writer = it }
                writer?.write(text)
                writer?.flush()
            } catch (error: Throwable) {
                log.warn("run job ${job.id}: cannot write the output file", error)
            }
        }
    }

    private fun openWriter(job: Job): BufferedWriter? {
        val path = job.outputPath ?: return null
        return try {
            Files.newBufferedWriter(Path.of(path), StandardCharsets.UTF_8)
        } catch (error: Throwable) {
            log.warn("run job ${job.id}: cannot open $path", error)
            null
        }
    }

    private fun closeWriter(job: Job) {
        synchronized(job) {
            try {
                job.writer?.close()
            } catch (error: Throwable) {
                log.warn("run job ${job.id}: cannot close the output file", error)
            }
            job.writer = null
        }
    }

    private fun tempFile(id: String): String? = try {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "dsh-ideadock", "jobs")
        Files.createDirectories(directory)
        directory.resolve("$id.log").toString()
    } catch (error: Throwable) {
        log.warn("cannot create the run job directory", error)
        null
    }

    private const val MAX_MEMORY = 256 * 1024
    private const val MAX_JOBS = 100
}
