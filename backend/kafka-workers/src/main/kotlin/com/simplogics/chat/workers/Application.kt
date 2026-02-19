package com.simplogics.chat.workers

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.Properties

fun main() {
    val logger = LoggerFactory.getLogger("KafkaWorkers")
    val props =
        Properties().apply {
            put("bootstrap.servers", "localhost:9092")
            put("group.id", "chat-workers-group")
            put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
        }

    val consumer = KafkaConsumer<String, String>(props)
    consumer.subscribe(listOf("chat-message-events"))

    logger.info("Starting Kafka Workers...")

    runBlocking {
        try {
            while (isActive) {
                val records = consumer.poll(Duration.ofMillis(1000))
                for (record in records) {
                    logger.info("Received event: key=${record.key()}, value=${record.value()}")
                    // Process event: Simulate sending a push notification
                    val messageJson = record.value()
                    launch {
                        try {
                            // In a real app, parse and send FCM/APNS here
                            logger.info("Processing notification for channel ${record.key()}...")
                            delay(500) // Simulate processing time
                            logger.info("Successfully processed notification for message record.")
                        } catch (e: Exception) {
                            logger.error("Failed to process notification", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error in worker", e)
        } finally {
            consumer.close()
        }
    }
}
