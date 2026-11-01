package darkmatter.androiduse.app.network

import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class SocketWriteDeadlineTest {
    @Test
    fun closesSocketToInterruptAWriteThatExceedsItsDeadline() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val socket = BlockingSocket()
        val output = BlockingOutput(socket)
        val writer = Executors.newSingleThreadExecutor()
        try {
            val write = writer.submit {
                SocketWriteDeadline.run(socket, scheduler, 100) { output.write(1) }
            }
            assertTrue(output.writeStarted.await(1, TimeUnit.SECONDS))
            try {
                write.get(2, TimeUnit.SECONDS)
                throw AssertionError("blocked write should be interrupted by socket closure")
            } catch (_: java.util.concurrent.ExecutionException) {
                assertTrue(socket.closed.await(0, TimeUnit.MILLISECONDS))
            }
        } finally {
            writer.shutdownNow()
            scheduler.shutdownNow()
        }
    }

    private class BlockingSocket : Socket() {
        val closed = CountDownLatch(1)
        override fun close() {
            closed.countDown()
        }
    }

    private class BlockingOutput(private val socket: BlockingSocket) : OutputStream() {
        val writeStarted = CountDownLatch(1)
        override fun write(value: Int) {
            writeStarted.countDown()
            if (!socket.closed.await(2, TimeUnit.SECONDS)) throw IOException("test write was not interrupted")
            throw IOException("socket closed during test write")
        }
    }
}
