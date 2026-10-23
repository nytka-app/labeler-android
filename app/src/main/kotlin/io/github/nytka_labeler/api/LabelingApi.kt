package io.github.nytka_labeler.api

import io.github.nytka_labeler.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException

/**
 * `/api/v1/labeling` over OkHttp. Reads the settings on every call. Never logs the token, and a failure carries a
 * fixed sentence or the system's own message, never the server's response text, except the field message of a 400.
 */
class LabelingApi(
    private val client: OkHttpClient,
    private val settings: suspend () -> Settings,
) : LabelingClient {
    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    override suspend fun tasks(): ApiResult<List<TaskInfo>> =
        call("GET", "tasks") { response ->
            json.decodeFromString<TasksResponse>(response.body.string()).tasks
        }

    override suspend fun next(
        task: String,
        limit: Int,
    ): ApiResult<NextResponse> =
        call("GET", "next", mapOf("task" to task, "limit" to limit.toString())) { response ->
            json.decodeFromString<NextResponse>(response.body.string())
        }

    override suspend fun clip(
        id: Long,
        context: Int,
        target: File,
    ): ApiResult<Unit> =
        call("GET", "$id/clip", mapOf("context" to context.toString())) { response ->
            val part = File(target.parentFile, target.name + ".part")
            response.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(target)) {
                part.delete()
                throw IOException("Could not store the clip.")
            }
        }

    override suspend fun answer(
        itemId: Long,
        body: String,
    ): AnswerResult {
        val target =
            when (val t = target()) {
                is Target.Missing -> return AnswerResult.Retry(t.reason)
                is Target.Ready -> t
            }
        return try {
            val requestBody = body.toRequestBody("application/json".toMediaType())
            execute(target, "POST", "$itemId", emptyMap(), requestBody).use { response ->
                when (response.code) {
                    200 -> AnswerResult.Sent(json.decodeFromString<AnswerReceipt>(response.body.string()).labelId)
                    400 -> rejected(response)
                    401, 403 -> AnswerResult.TokenRefused
                    404 -> AnswerResult.ItemGone
                    else -> AnswerResult.Retry("The server answered ${response.code}.")
                }
            }
        } catch (e: IOException) {
            AnswerResult.Retry(e.message ?: "Network error")
        } catch (_: SerializationException) {
            AnswerResult.Retry("Unexpected answer from the server")
        }
    }

    private fun rejected(response: Response): AnswerResult =
        try {
            val problem = json.decodeFromString<FieldError>(response.body.string())
            AnswerResult.Rejected(problem.field, problem.error)
        } catch (_: SerializationException) {
            AnswerResult.Rejected("", "The server rejected this answer.")
        }

    private suspend fun <T> call(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        read: (Response) -> T,
    ): ApiResult<T> {
        val target =
            when (val t = target()) {
                is Target.Missing -> return ApiResult.Failure(FailureKind.NotConfigured, t.reason)
                is Target.Ready -> t
            }
        return try {
            execute(target, method, path, query, null).use { response ->
                if (response.code in 200..299) ApiResult.Ok(read(response)) else failure(response.code)
            }
        } catch (_: InterruptedIOException) {
            ApiResult.Failure(FailureKind.Timeout, "The server took too long to answer.")
        } catch (e: IOException) {
            ApiResult.Failure(FailureKind.Network, e.message ?: "Network error")
        } catch (_: SerializationException) {
            ApiResult.Failure(FailureKind.Server, "Unexpected answer from the server")
        }
    }

    private fun failure(code: Int): ApiResult.Failure =
        when (code) {
            401 -> ApiResult.Failure(FailureKind.Unauthorized, "The server refused the token.")
            403 -> ApiResult.Failure(FailureKind.Forbidden, "The token needs the labeling scope.")
            404 -> ApiResult.Failure(FailureKind.NotFound, "Not found.")
            405 -> ApiResult.Failure(FailureKind.Unsupported, "The server does not know this call.")
            else -> ApiResult.Failure(FailureKind.Server, "The server answered $code.")
        }

    private suspend fun execute(
        target: Target.Ready,
        method: String,
        path: String,
        query: Map<String, String>,
        body: okhttp3.RequestBody?,
    ): Response {
        val url =
            target.base
                .newBuilder()
                .addEncodedPathSegments("api/v1/labeling/$path")
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer ${target.token}")
                .method(method, body)
                .build()
        return withContext(Dispatchers.IO) { client.newCall(request).execute() }
    }

    private suspend fun target(): Target {
        val current = settings()
        if (current.token.isEmpty()) return Target.Missing("No token is set.")
        return when (val check = ServerUrl.check(current.serverUrl, current.privateNetwork)) {
            is UrlCheck.Ok -> Target.Ready(check.base, current.token)
            is UrlCheck.Invalid -> Target.Missing(check.reason)
        }
    }

    private sealed interface Target {
        data class Ready(
            val base: HttpUrl,
            val token: String,
        ) : Target

        data class Missing(
            val reason: String,
        ) : Target
    }
}
