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

    /**
     * 快递取件码正则
     *
     * 支持格式：
     * - 纯数字：06675, 123456
     * - 带连字符/空格分段：3-1-7679, 1-234, 2-3-456, 12 345
     * - 字母前缀：YT5667, ab1234
     * - 字母+连字符混合：A-123
     *
     * 匹配上下文关键词："取件码为XX"、"凭取件码XX"、"取件码:XX"、"码XX"
     */
    private val expressPattern = Regex(
        // 模式1：(关键词) + (分隔符) + 取件码 —— 最常见
        """(?:取件码|提货码|快递码|验证码|自提码|取件密码|取货码)[是为：:\s]*""" +
        """([A-Za-z0-9][A-Za-z0-9\- ]{0,10}[A-Za-z0-9])""" +
        // 模式2：取件码 + (分隔符) + (后缀关键词) —— "请凭 3-1-7679 取件"
        """|(?<=[^0-9A-Za-z])([A-Za-z0-9][A-Za-z0-9\- ]{0,10}[A-Za-z0-9])""" +
        """(?:\s*[，,。.\s]?\s*(?:取件|提货|自提|领取))"""
    )

    /**
     * 驿站地址正则
     *
     * 支持格式：
     * - "已到达/已到/已送达 xx 店/门店/驿站/站点/快递点/代收点/服务点/超市/便利店/柜"
     * - "地址: xx" / "地址：xx"
     * - "存放至/存放于/请前往/请到/至 xx (地点关键词)"
     * - 【xx快递/驿站】... 已到达 ... 地点
     * - "包裹到了 xx (地点)"
     * - "已至 xx 门店/(自提点)"
     */
    private val addressPattern = Regex(
        // 模式1（最高优先级）：明确 "地址:" / "地址：" 前缀 —— 最精准
        """(?:地址)[：:\s]*([^，,。.\n\r；;]{2,30}?)(?=\s*[，,。.\n\r；;]|$)""" +
        // 模式2：状态动词 + 地点 + 场所类型后缀（"已到达XX店"、"存放到XX丰巢柜"）
        """|(?:已到达|已到|已送达|存放到|存放至|存放于)\s*([^，,。.\n\r]{2,30}?)(?:店|门店|驿站|站点|快递点|代收点|自提点|服务点|超市|便利店|门卫|丰巢|包裹柜|柜)""" +
        // 模式3："请前往/请到/至" + 地点 + 动作/场所
        """|(?:请前往|请到|至)\s*([^，,。.\n\r]{2,25}?)(?:取件|自提|领取|门店|驿站|站点|快递|超市|便利店|柜)""" +
        // 模式4："包裹到了/已至/送至" + 地点
        """|(?:包裹到了|已至|送至)\s*([^，,。.\n\r]{2,25}?)(?:店|门店|驿站|\(自提点\)|，)""" +
        // 模式5：【签名】内嵌套的地点信息（放最后避免误匹配签名名）
        """|[【\[](?:[^】\]]+?)[】\]][^，,。.\n\r]{0,5}(?:您有包裹)?[^，,。.\n\r]{0,3}(?:已到达|已到|已送达)\s*([^，,。.\n\r]{2,25}?)(?:店|门店|驿站|站点|快递)"""
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
            val raw = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
            val code = normalizeCode(raw)
            if (code.isNotEmpty() && isValidExpressCode(code)) {
                val address = extractAddress(text)
                return CodeRecord(
                    code = code,
                    codeType = CodeType.EXPRESS,
                    rawText = m.value,
                    address = address
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
     * 从快递通知文本中提取驿站地址
     *
     * 支持的短信模板格式：
     * - 【兔喜生活】您有包裹已到达长沙天心紫湖香醍12栋店，取件码为3-1-7679
     * - 【菜鸟驿站】您有包裹已到长沙五矿紫湖香缇南门9栋门店，取件码06675
     * - 【丰巢】您的包裹已存放到XX小区丰巢柜，取件码1234
     * - 包裹已到XX驿站，请凭取件码5678取件
     * - 地址:紫湖香醍12栋惠捷便利店
     * - 请凭取件码886523，至朝阳区建国路88号菜鸟驿站取件
     * - 包裹到了中关村3号楼1层驿站
     * - 您的订单XX已至望京SOHO门店（自提点），地址：北京市朝阳区望京街9号
     *
     * @return 提取到的地址文本，未匹配返回空字符串
     */
    private fun extractAddress(text: String): String {
        // 使用 findAll 遍历所有匹配，而不仅仅是第一个
        addressPattern.findAll(text).forEach { m ->
            // 遍历当前匹配的所有捕获组（跳过 groupValues[0] 是完整匹配）
            for (i in 1 until m.groupValues.size) {
                val addr = m.groupValues[i].trim()
                if (addr.isNotEmpty() && addr.length >= 2 && !looksLikeSignatureName(addr)) {
                    return addr
                }
            }
        }
        return ""
    }

    /**
     * 判断提取的"地址"是否实际是快递公司/平台签名名称
     *
     * 签名特征：纯品牌名、不含地点信息（无省市区/路/栋/号/小区等）
     */
    private fun looksLikeSignatureName(candidate: String): Boolean {
        // 已知签名品牌名白名单（这些不是真实地址）
        val signatures = setOf(
            "兔喜生活", "兔喜", "菜鸟驿站", "菜鸟", "丰巢", "速递",
            "中通", "圆通", "申通", "韵达", "顺丰", "京东", "邮政",
            "极兔", "丹鸟", "德邦", "韵达", "百世"
        )
        if (candidate in signatures) return true

        // 如果候选词是签名的子集且很短（<=4字），大概率是签名
        if (candidate.length <= 4) {
            for (sig in signatures) {
                if (sig.contains(candidate)) return true
            }
        }

        // 含有典型地址特征关键词 → 肯定不是签名
        val addressIndicators = listOf("市", "区", "县", "镇", "乡", "村", "路",
            "街", "栋", "号", "层", "单元", "小区", "大厦", "广场",
            "中心", "花园", "苑", "城", "巷", "弄")
        if (addressIndicators.any { candidate.contains(it) }) return false

        // 纯2~4字的中文且不含地址特征 + 不在已知列表 → 可疑，但不拦截（避免漏判）
        return false
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

    /**
     * 标准化取件码：去除多余空格，统一连字符格式
     *
     * 输入示例："3-1-7679" → "3-1-7679"
     *         "1 234" → "1-234"
     *         "  06675 " → "06675"
     */
    private fun normalizeCode(raw: String): String {
        return raw.trim()
            .replace(Regex("""\s+"""), "-")   // 空格转连字符
            .replace(Regex("""-{2,}"""), "-")   // 多连字符合并
            .trim('-')
    }

    /**
     * 验证提取到的字符串是否像合法的快递取件码
     *
     * 合法规则：
     * - 总长度（去掉分隔符后）在 2~12 位之间
     * - 不能是纯手机号（11位纯数字）
     * - 不能是纯时间戳/长数字（>12位）
     * - 不能全为连字符或空格
     */
    private fun isValidExpressCode(code: String): Boolean {
        if (code.isEmpty()) return false

        // 去掉连字符后检查有效字符长度
        val digits = code.replace(Regex("""[-\s]"""), "")
        if (digits.length < 2 || digits.length > 12) return false

        // 排除手机号（11位纯数字且无连字符）
        if (Regex("""^\d{11}$""").matches(code)) return false

        // 排除看起来不像码的纯长数字（>8位且无连字符无字母）
        if (Regex("""^\d{9,}$""").matches(code)) return false

        // 至少包含一个数字或字母
        if (!Regex("""[A-Za-z0-9]""").containsMatchIn(code)) return false

        return true
    }
}
