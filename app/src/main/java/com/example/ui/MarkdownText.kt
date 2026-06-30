package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = text.split("\n")
        var inCodeBlock = false
        var codeBlockContent = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // Render code block
                    CodeBlock(codeBlockContent.toString().trimEnd())
                    codeBlockContent = StringBuilder()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                continue
            }

            // Parse Headers
            if (trimmed.startsWith("#")) {
                val headerLevel = trimmed.takeWhile { it == '#' }.length
                val headerText = trimmed.drop(headerLevel).trim()
                val style = when (headerLevel) {
                    1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = color)
                    2 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = color)
                    else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = color)
                }
                Text(text = parseInlineMarkdown(headerText), style = style)
                continue
            }

            // Parse Lists
            if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                val listText = trimmed.substring(1).trim()
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text("•  ", color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(text = parseInlineMarkdown(listText), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                continue
            }

            if (trimmed.isNotEmpty() && trimmed[0].isDigit() && trimmed.contains(". ")) {
                val dotIndex = trimmed.indexOf(". ")
                val num = trimmed.substring(0, dotIndex + 1)
                val listText = trimmed.substring(dotIndex + 2).trim()
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text("$num  ", color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(text = parseInlineMarkdown(listText), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                continue
            }

            // Normal text line or empty line
            if (line.isNotEmpty()) {
                Text(text = parseInlineMarkdown(line), color = color, style = MaterialTheme.typography.bodyMedium)
            } else {
                Spacer(modifier = Modifier.height(2.dp))
            }
        }

        // Handle unclosed code block gracefully
        if (inCodeBlock && codeBlockContent.isNotEmpty()) {
            CodeBlock(codeBlockContent.toString().trimEnd())
        }
    }
}

@Composable
fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2D2D2D), shape = RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = code,
            color = Color(0xFFF8F8F2),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold-Italic (*** or ___)
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else {
                        append("***")
                        i += 3
                    }
                }
                // Bold (** or __)
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                // Italic (* or _)
                text.startsWith("*", i) -> {
                    val end = text.indexOf("*", i + 1)
                    if (end != -1) {
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append("*")
                        i += 1
                    }
                }
                // Inline Code (`code`)
                text.startsWith("`", i) -> {
                    val end = text.indexOf("`", i + 1)
                    if (end != -1) {
                        withStyle(style = SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x33808080))) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append("`")
                        i += 1
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
