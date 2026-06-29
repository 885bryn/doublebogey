package com.doublebogey.golftracer

enum class RoleChoice(val persistedName: String, val label: String) {
    Camera("camera", "Camera"),
    Display("display", "Display");

    companion object {
        fun fromPersisted(value: String?): RoleChoice? =
            entries.firstOrNull { it.persistedName == value }
    }
}
