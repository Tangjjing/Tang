package com.dschat.app.data.remote

import okhttp3.OkHttpClient

/**
 * One process-wide OkHttp base client. Every API / tool client derives from this via [newBuilder]
 * (DeepSeekApi, AnthropicApi, the agent tools' ToolHttp) so they all share a single connection pool,
 * dispatcher and worker-thread pools instead of standing up three independent stacks. That means
 * fewer idle threads and — crucially — reused keep-alive connections to the same host, which cuts the
 * TLS-handshake cost off repeat requests and lowers time-to-first-token.
 *
 * Per-use timeouts (e.g. the streaming clients' unlimited read timeout) are applied on the derived
 * builder, not here, since they differ per caller.
 */
object SharedHttp {
    val base: OkHttpClient = OkHttpClient.Builder().build()
}
