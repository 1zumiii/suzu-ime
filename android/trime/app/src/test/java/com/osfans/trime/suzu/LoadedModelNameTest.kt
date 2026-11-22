package com.osfans.trime.suzu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LoadedModelNameTest : StringSpec({
    "同名 GGUF 按实际权重区分" {
        loadedModelName("uer-gpt2-suzu", "953fa76e") shouldBe "Suzu-UER6-KD500"
        loadedModelName("uer-gpt2-suzu", "44e0cc85") shouldBe "uer-gpt2-suzu·44e0cc85"
        loadedModelName("rime-student", "a2ff2c46") shouldBe "Suzu-2.4"
    }
    "等待哈希或使用旧守护进程时不猜版本" {
        loadedModelName("uer-gpt2-suzu", "") shouldBe "uer-gpt2-suzu"
        loadedModelName("", "") shouldBe "未知模型"
    }
})
