package io.github.nytka_labeler.ui

import io.github.nytka_labeler.api.MIXED

private val TASK_NAMES =
    mapOf(
        MIXED to "Mixed",
        "device" to "Device or live voice",
        "voice" to "My voice",
        "quiet" to "Quiet lines",
        "transcript" to "Transcript",
        "overlap" to "Overlap",
    )

fun taskName(task: String): String = TASK_NAMES[task] ?: task
