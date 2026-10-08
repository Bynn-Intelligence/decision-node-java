package com.bynn.decisionnode

import com.bynn.decisionnode.Questions.choice
import com.bynn.decisionnode.Questions.score
import com.bynn.decisionnode.Questions.truth
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The SDK as a Kotlin caller writes it. */
class KotlinUsageTest {
    private lateinit var server: HttpServer
    private lateinit var url: String

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            ex.requestBody.readAllBytes()
            val body = """{"model":"decisionnode-1.0","answers":{
                "route":{"type":"choice","choice":"billing","confidence":0.8,"probabilities":{"billing":0.87,"bug":0.08,"account":0.05}},
                "urgency":{"type":"score","score":2.31,"confidence":0.5,"legend":{"0":"routine","1":"today","2":"urgent","3":"critical"},"probabilities":{"0":0.05,"1":0.15,"2":0.24,"3":0.56}},
                "escalate":{"type":"truth","truth":0.94}},"usage":{"input_tokens":40,"output_tokens":0}}""".toByteArray()
            ex.responseHeaders.add("x-decisionnode-safety", "harm=0.01, self_harm=0.00")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun readsNaturallyFromKotlin() {
        val dn = DecisionNode.builder().apiKey("dn_test").baseUrl(url).timeout(Duration.ofSeconds(5)).build()

        val d = dn.decide(
            mapOf("ticket" to "I was charged twice and nobody has replied for 3 days."),
            mapOf(
                "route" to choice("Where should this go?", mapOf("billing" to "money", "bug" to "broken", "account" to "login")),
                "urgency" to score("How urgent is this?", listOf("routine", "today", "urgent", "critical")),
                "escalate" to truth("Escalate to a human now?"),
            ),
        )

        assertEquals("billing", d.choice("route").choice())
        assertEquals(0.87, d.choice("route").probabilities()["billing"])
        assertEquals(2.31, d.score("urgency").score())
        assertEquals(0.94, d.truth("escalate").truth())
        assertEquals(40L, d.usage().inputTokens())
        assertTrue(d.safety()!!.checked())

        val answer = d["escalate"]
        val text = when (answer) {
            is TruthAnswer -> "truth ${answer.truth()}"
            is ChoiceAnswer -> "choice ${answer.choice()}"
            is ScoreAnswer -> "score ${answer.score()}"
            is NumberAnswer -> "number ${answer.number()}"
            is PointAnswer -> "point ${answer.x()} ${answer.y()}"
            is BoxAnswer -> "box ${answer.x1()} ${answer.y1()} ${answer.x2()} ${answer.y2()}"
            is UnknownAnswer -> "unknown ${answer.type()}"
        }
        assertEquals("truth 0.94", text)
    }

    @Test
    fun videosReadNaturallyFromKotlin() {
        val dn = DecisionNode.builder().apiKey("dn_test").baseUrl(url).timeout(Duration.ofSeconds(5)).build()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val dock = Video.of("dock", listOf(0.0, 0.5, 1.0).map { VideoFrame.fromBytes(it, jpeg) }).fps(2.0)

        val d = dn.decide(
            DecideRequest.builder()
                .state("Warehouse camera 4, loading dock, shift B.")
                .video(dock)
                .question("escalate", truth("Does a person fall in the video?"))
                .build(),
        )

        assertEquals(0.94, d.truth("escalate").truth())
        assertEquals(emptyList<String>(), d.warnings())
    }

    @Test
    fun errorsAreKotlinExceptions() {
        val dn = DecisionNode.builder().apiKey("").baseUrl(url).build()
        val e = assertThrows<DecisionNodeError> { dn.check("s", "?") }
        assertTrue(e is AuthenticationError)
    }
}
