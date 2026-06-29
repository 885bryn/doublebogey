package com.doublebogey.golftracer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoleChoiceTest {
    @Test
    fun resolvesPersistedCameraRole() {
        assertEquals(RoleChoice.Camera, RoleChoice.fromPersisted("camera"))
    }

    @Test
    fun resolvesPersistedDisplayRole() {
        assertEquals(RoleChoice.Display, RoleChoice.fromPersisted("display"))
    }

    @Test
    fun rejectsMissingOrUnknownPersistedRole() {
        assertNull(RoleChoice.fromPersisted(null))
        assertNull(RoleChoice.fromPersisted("server"))
    }
}
