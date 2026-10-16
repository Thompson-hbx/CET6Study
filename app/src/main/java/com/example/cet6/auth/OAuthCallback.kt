package com.example.cet6.auth

import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

internal fun callbackParameters(requestLine: String, state: String): Map<String, String>? = try {
    val parts = requestLine.split(' ')
    if (parts.size != 3 || parts[0] != "GET" || parts[2] != "HTTP/1.1") null else {
        val uri = URI(parts[1])
        if (uri.isAbsolute || uri.rawAuthority != null || uri.rawPath != "/auth/callback" || uri.rawFragment != null) null else {
            val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val params = pairs.toMap()
            params.takeIf { pairs.size == params.size && it["state"] == state }
        }
    }
} catch (_: Exception) { null }

/** Bound to one authorization attempt; unrelated requests never consume that attempt. */
internal fun receiveOAuthCallback(listener: ServerSocket, state: String, timeoutMillis: Long = 180_000): Map<String, String> {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        listener.soTimeout = minOf(1000L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
            .coerceAtLeast(1).toInt()
        val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
        socket.use {
            socket.soTimeout = minOf(5000L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
                .coerceAtLeast(1).toInt()
            val params = try {
                val input = socket.getInputStream().buffered()
                val request = StringBuilder()
                var headerEnd = false
                while (request.length < 16_384 && System.nanoTime() < deadline) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val byte = input.read()
                    if (byte == -1) break
                    request.append(byte.toChar())
                    if (request.endsWith("\r\n\r\n")) { headerEnd = true; break }
                }
                if (headerEnd) callbackParameters(request.toString().substringBefore("\r\n"), state) else null
            } catch (_: java.io.IOException) { null }
            val message = if (params != null) "回调已收到，请手动返回六级研习室。" else "无效回调，请继续原授权流程。"
            val html = "<!doctype html><meta charset='utf-8'><title>CET6 Study</title><p>$message</p>"
                .toByteArray(Charsets.UTF_8)
            try {
                socket.getOutputStream().apply {
                    val status = if (params != null) "200 OK" else "400 Bad Request"
                    write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${html.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                    write(html); flush()
                }
            } catch (_: java.io.IOException) { /* A closed browser does not invalidate a received code. */ }
            if (params != null) return params
        }
    }
    throw SocketTimeoutException("等待 ChatGPT 登录超时，请重试")
}
