package io.github.nytka_labeler.support

import io.github.nytka_labeler.api.LabelItem
import io.github.nytka_labeler.api.NextResponse
import io.github.nytka_labeler.api.TaskInfo
import io.github.nytka_labeler.api.TasksResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/** One recorded `POST /{id}`. */
data class Post(
    val itemId: Long,
    val body: String,
    val authorization: String?,
)

/**
 * The labeling API of docs/api.md as a MockWebServer: tasks, a session of items, clips (the synthetic tone), and
 * answers. A repeated `clientId` returns the first label id and stores nothing new. [postCodes] scripts the next
 * answers' status codes; an empty queue means 200.
 */
class FakeLabelingServer : Dispatcher() {
    val server = MockWebServer()
    val posts = mutableListOf<Post>()
    val requestPaths = mutableListOf<String>()
    val postCodes = ArrayDeque<Int>()
    var tasks: List<TaskInfo> = emptyList()
    var next: NextResponse = NextResponse("s-1", "", emptyList())
    var missingClips: Set<String> = emptySet()
    var fieldError = """{"field":"source","error":"unknown value"}"""

    private val labels = mutableMapOf<String, Long>()
    val labelCount get() = labels.size
    private val tone =
        checkNotNull(checkNotNull(javaClass.classLoader).getResourceAsStream("tone.ogg")).use {
            it.readBytes()
        }
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        server.dispatcher = this
        server.start()
    }

    fun stop() = server.close()

    val url: String get() = server.url("/").toString()

    fun serve(items: List<LabelItem>) {
        next = NextResponse("s-1", "2026-10-24T12:30:00Z", items)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.encodedPath.removePrefix("/api/v1/labeling/")
        requestPaths += request.url.toString().removePrefix(url)
        return when {
            request.method == "GET" && path == "tasks" -> ok(Json.encodeToString(TasksResponse(tasks)))
            request.method == "GET" && path == "next" -> ok(Json.encodeToString(next))
            request.method == "GET" && path.endsWith("/clip") -> clip(path, request)
            request.method == "POST" -> answer(path, request)
            else -> MockResponse.Builder().code(404).build()
        }
    }

    private fun clip(
        path: String,
        request: RecordedRequest,
    ): MockResponse {
        val key = path + "?" + request.url.query
        if (key in missingClips) return MockResponse.Builder().code(404).build()
        return MockResponse
            .Builder()
            .code(200)
            .setHeader("Content-Type", "audio/ogg")
            .body(Buffer().write(tone))
            .build()
    }

    private fun answer(
        path: String,
        request: RecordedRequest,
    ): MockResponse {
        val body = request.body?.utf8().orEmpty()
        posts += Post(path.toLong(), body, request.headers["Authorization"])
        return when (val code = postCodes.removeFirstOrNull() ?: 200) {
            200 -> {
                val clientId =
                    json
                        .parseToJsonElement(body)
                        .jsonObject
                        .getValue("clientId")
                        .jsonPrimitive.content
                val label = labels.getOrPut(clientId) { labels.size + 1L }
                ok("""{"labelId":$label,"superseded":null}""")
            }
            400 ->
                MockResponse
                    .Builder()
                    .code(400)
                    .body(fieldError)
                    .build()
            else ->
                MockResponse
                    .Builder()
                    .code(code)
                    .body("secret server text")
                    .build()
        }
    }

    private fun ok(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .setHeader("Content-Type", "application/json")
            .body(body)
            .build()
}
