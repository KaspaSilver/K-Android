package com.kachat.app.util

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer

/** An indexer answer bigger than any real one: it isn't read (iOS audit 2026-11-21 #19). */
class ResponseTooLargeException(message: String) : java.io.IOException(message)

/**
 * Caps every answer this client reads at [maxBytes] for its request (iOS audit 2026-11-21 #19,
 * the indexers' `maxResponseBytes`): a declared length past it fails before anything is read, and
 * a body that runs past it fails as it is read, with [ResponseTooLargeException] either way.
 */
fun OkHttpClient.Builder.capResponseBodies(what: String, maxBytes: (Request) -> Long): OkHttpClient.Builder =
    addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        val body = response.body ?: return@addInterceptor response
        val max = maxBytes(chain.request())
        if (body.contentLength() > max) {
            response.close()
            throw ResponseTooLargeException("$what sent ${body.contentLength()} bytes, more than an answer can hold")
        }
        val capped = object : okio.ForwardingSource(body.source()) {
            private var total = 0L
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) {
                    total += read
                    if (total > max) throw ResponseTooLargeException("$what sent more than an answer can hold")
                }
                return read
            }
        }
        response.newBuilder()
            .body(capped.buffer().asResponseBody(body.contentType(), body.contentLength()))
            .build()
    }
