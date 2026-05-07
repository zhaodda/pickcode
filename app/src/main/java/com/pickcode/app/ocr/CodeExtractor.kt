package com.pickcode.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pickcode.app.data.model.CodeRecord
import com.pickcode.app.data.model.CodeType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR 识别 + 验证码提取核心类
 */
class CodeExtractor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ========== 正则规则 ==========
    // 快递取件码：4~8 位数字（或含1~2位字母）
    private val expressPattern = Regex(
        """(?:取件码|提货码|快递码|验证码|自提码)[：:\s]*([A-Za-z0-9]{4,8})""" +
        """|([A-Za-z0-9]{4,8})(?:\s*[，,]?\s*(?:取件|提货|快递))"""
    )

    // 奶茶/外卖取餐码：3~6 位纯数字，或带字母前缀
    private val foodPattern = Regex(
        """(?:取餐号|取餐码|餐号|自提|订单号)[：:\s]*([A-Za-z]{0,2}[0-9]{3,6})""" +
        """|([A-Za-z]{0,2}[0-9]{3,6})(?:\s*[，,]?\s*(?:取餐|自提|到店))"""
    )

    // 停车场取车码
    private val parkingPattern = Regex(
        """(?:取车码|停车|车牌)[：:\s]*([A-Z]{1,3}[0-9]{1,4})"""
    )

    // 通用纯数字验证码：4~8 位数字（兜底）
    private val genericPattern = Regex(
        """(?<![0-9])([0-9]{4,8})(?![0-9])"""
    )

    /**
     * 从 Bitmap 中识别并提取验证码
     * 返回提取到的 CodeRecord，若无匹配则返回 null
     */
    suspend fun extractFromBitmap(bitmap: Bitmap): CodeRecord? {
        val fullText = runOcr(bitmap) ?: return null
        return parseCode(fullText)
    }

    /**
     * 从纯文本中直接提取验证码（无障碍节点树文字提取入口）
     * 无需 OCR，直接对文本做正则匹配。
     *
     * @param text 屏幕文字内容
     * @return 提取到的 CodeRecord，无匹配返回 null
     */
    fun extractFromText(text: String): CodeRecord? {
        return if (text.isBlank()) null else parseCode(text)
    }

    private suspend fun runOcr(bitmap: Bitmap): String? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text.ifBlank { null })
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }

    /**
     * 对 OCR 文本应用正则规则，按优先级匹配
     */
    fun parseCode(text: String): CodeRecord? {
        // 1. 快递取件码
        expressPattern.find(text)?.let { m ->
            val code = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            if (code.isNotEmpty()) {
                return CodeRecord(
                    code = code,
                    codeType = CodeType.EXPRESS,
                    rawText = m.value
                )
            }
        }

        // 2. 餐饮取餐码
        foodPattern.find(text)?.let { m ->
            val code = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            if (code.isNotEmpty()) {
                return CodeRecord(
                    code = code,
                    codeType = CodeType.FOOD,
                    rawText = m.value
                )
            }
        }

        // 3. 停车取车码
        parkingPattern.find(text)?.let { m ->
            val code = m.groupValues[1].trim()
            if (code.isNotEmpty()) {
                return CodeRecord(
                    code = code,
                    codeType = CodeType.PARKING,
                    rawText = m.value
                )
            }
        }

        // 4. 通用数字兜底（取置信度最高的一个）
        val candidates = genericPattern.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.length in 4..8 }
            .toList()

        if (candidates.isNotEmpty()) {
            val best = candidates.maxByOrNull { scoreCandidate(it, text) } ?: candidates.first()
            return CodeRecord(
                code = best,
                codeType = CodeType.OTHER,
                rawText = text.take(120)
            )
        }

        return null
    }

    /**
     * 候选评分：离关键词越近、位数越典型分越高
     */
    private fun scoreCandidate(code: String, fullText: String): Int {
        var score = 0
        val keywords = listOf("取件", "取餐", "验证", "码", "自提", "提货")
        val idx = fullText.indexOf(code)
        keywords.forEach { kw ->
            val ki = fullText.indexOf(kw)
            if (ki >= 0) score += maxOf(0, 20 - Math.abs(ki - idx))
        }
        // 6 位最典型
        score += when (code.length) {
            6 -> 10
            4, 5 -> 6
            else -> 2
        }
        return score
    }
}
