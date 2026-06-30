package com.example

import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun extractResponseText_validJson_extractsText() {
    val json = """
      {
        "thought_process": "Preparing response...",
        "execute_action": null,
        "response_text": "Hello Alexander, I am ready."
      }
    """.trimIndent()
    val result = extractResponseText(json)
    assertEquals("Hello Alexander, I am ready.", result)
  }

  @Test
  fun extractResponseText_noResponseText_fallbackToThought() {
    val json = """
      {
        "thought_process": "Working on finding the solution...",
        "execute_action": null
      }
    """.trimIndent()
    val result = extractResponseText(json)
    assertEquals("Thinking: Working on finding the solution...", result)
  }

  @Test
  fun extractResponseText_nonJson_returnsInput() {
    val text = "Error: model could not load correctly"
    val result = extractResponseText(text)
    assertEquals("Error: model could not load correctly", result)
  }

  @Test
  fun persona_containsDefaultPersonas() {
    val personas = Persona.DEFAULT_PERSONAS
    assertEquals(3, personas.size)
    assertNotNull(personas.find { it.name == "Concise Assistant" })
    assertNotNull(personas.find { it.name == "Creative Writer" })
    assertNotNull(personas.find { it.name == "Technical Debugger" })
  }
}
