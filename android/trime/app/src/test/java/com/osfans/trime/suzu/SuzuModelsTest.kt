package com.osfans.trime.suzu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private fun prim(v: Any): JsonElement = if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString())

private fun entry(vararg overrides: Pair<String, Any>): JsonObject =
    JsonObject(
        mapOf<String, Any>(
            "id" to "KD500",
            "title" to "Suzu-UER6-KD500",
            "repo" to "IzumiThe3rd/Suzu-UER6-KD500",
            "revision" to "4a8fd6831e482569fd4503cc19ce3022e75cc027",
            "file" to "suzu-uer6-kd500.q8_0.gguf",
            "sha256" to "953fa76eca9d1b171cf9e819e9f0474f12c309be1bc7c1aad46db2916d0b3859",
            "size" to 52_811_488,
            "vocab_sha256" to "16a9b481d23159418c6455d76f36569a9d97d6ce1d5303f5431ce0c621e4fc7c",
            "vocab_size" to 20_573,
            "top_gate" to "1.5",
        ).plus(overrides).mapValues { prim(it.value) },
    )

private fun catalog(vararg entries: JsonObject, schema: Int = 1): String =
    JsonObject(mapOf("schema" to JsonPrimitive(schema), "models" to JsonArray(entries.toList()))).toString()

class SuzuModelsTest : StringSpec({
    "仓库里的 models.json 与内置列表一致，顺序也相同" {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { it.resolve("models.json") }.first { it.isFile }
        SuzuModels.parse(file.readText(), appVersion = Int.MAX_VALUE) shouldBe SuzuModels.BUILTIN
    }
    "合格条目解析出下载地址" {
        val m = SuzuModels.parse(catalog(entry()), appVersion = 1)!!.single()
        m.files.first().url shouldBe
            "https://huggingface.co/IzumiThe3rd/Suzu-UER6-KD500/resolve/4a8fd6831e482569fd4503cc19ce3022e75cc027/suzu-uer6-kd500.q8_0.gguf"
        m.files.last().url shouldBe m.files.first().url + ".vocab.txt"
    }
    "指向别处、缺校验或门槛格式不对的条目跳过" {
        val bad = listOf(
            entry("id" to "a", "repo" to "someone/Suzu"),
            entry("id" to "b", "repo" to "IzumiThe3rd/../x"),
            entry("id" to "c", "file" to "../x.gguf"),
            entry("id" to "d", "revision" to "main"),
            entry("id" to "e", "sha256" to ""),
            entry("id" to "f", "top_gate" to "1.5\n  evil: 1"),
            entry("id" to "g", "size" to 0),
        )
        SuzuModels.parse(catalog(*bad.toTypedArray()), appVersion = 1).shouldBeNull()
        SuzuModels.parse(catalog(*(bad + entry()).toTypedArray()), appVersion = 1)!!.map { it.id } shouldBe listOf("KD500")
    }
    "要求更新版本 App 的条目不显示，格式版本不对整体不用" {
        SuzuModels.parse(catalog(entry("min_app" to 100), entry("id" to "x")), appVersion = 99)!!.map { it.id } shouldBe listOf("x")
        SuzuModels.parse(catalog(entry(), schema = 2), appVersion = 1).shouldBeNull()
        SuzuModels.parse("not json").shouldBeNull()
    }
})
