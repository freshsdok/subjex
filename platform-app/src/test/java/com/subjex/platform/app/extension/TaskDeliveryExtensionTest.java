package com.subjex.platform.app.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TaskDeliveryExtensionTest {

    @Test
    void extensionNameIsFixedAtCompileTime() {
        assertEquals("task-delivery", new TaskDeliveryExtension().extensionName());
    }
}
