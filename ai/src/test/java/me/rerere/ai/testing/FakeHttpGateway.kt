package me.rerere.ai.testing

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 起一个本地假网关，只用来验证「上游返回错误响应时 provider 怎么收场」。
 *
 * 用法：`withFakeGateway(502, "text/html", "<html>...</html>") { baseUrl -> ... }`，
 * 把 baseUrl 塞进 ProviderSetting 就能让请求打到这个只会回错误的小服务器上。
 */
fun withFakeGateway(
    status: Int,
    contentType: String,
    body: String,
    block: (baseUrl: String) -> Unit,
) {
    val server = ServerSocket(0)
    val worker = thread(isDaemon = true) {
        runCatching {
            while (!server.isClosed) {
                server.accept().use { socket ->
                    readRequestHead(socket)
                    val payload = body.toByteArray()
                    socket.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 $status Fake\r\n" +
                                    "Content-Type: $contentType\r\n" +
                                    "Content-Length: ${payload.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray()
                        )
                        write(payload)
                        flush()
                    }
                }
            }
        }
    }
    try {
        block("http://127.0.0.1:${server.localPort}")
    } finally {
        server.close()
        worker.join(1_000)
    }
}

private fun readRequestHead(socket: Socket) {
    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    while (true) {
        val line = reader.readLine() ?: return
        if (line.isEmpty()) return
    }
}
