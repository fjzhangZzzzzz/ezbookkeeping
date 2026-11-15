package top.fjzhang233.books

import org.junit.Assert.*
import org.junit.Test

class AccountPresetTest {
    @Test fun namedProviderPresetsResolveToTheirActualSharedWebIconMeaning(){
        val source=generateSequence(java.io.File(System.getProperty("user.dir"))){it.parentFile}.take(6).map{java.io.File(it,"src/consts/icon.ts")}.firstOrNull{it.isFile}
            ?: error("Shared Web icon table must be available; this test cannot certify a guessed ID")
        val accountIcons=source.readText().substringAfter("export const ALL_ACCOUNT_ICONS:").substringBefore("export const DEFAULT_ACCOUNT_ICON:")
        val actual=Regex("""'(\d+)':\s*\{\s*icon:\s*'([^']+)'""").findAll(accountIcons).associate{it.groupValues[1] to it.groupValues[2]}
        val expected=mapOf("wechat" to "lab la-weixin","alipay" to "lab la-alipay","qq-wallet" to "lab la-qq","paypal" to "lab la-paypal","digital-cny" to "las la-yen-sign","crypto" to "lab la-bitcoin")
        expected.forEach{(provider,meaning)->assertEquals(provider,meaning,actual[accountPreset(provider)!!.icon])}
    }
    @Test fun presetsOnlyTargetRealBackendTypesAndBundledIcons(){
        val all=accountPresetGroups.flatMap{it.presets};assertEquals(47,all.size);assertEquals(all.size,all.map{it.key}.toSet().size)
        val bundled=setOf("1","10","20","100","500","540","600","601","700","701","800","801","910","990","1003","1500","8000","8300","8301","8302")
        assertTrue(all.all{it.category in 1..9&&it.icon in bundled&&Regex("[0-9A-F]{6}").matches(it.color)})
        assertEquals(1,accountPreset("cash")!!.category);assertEquals(3,accountPreset("credit-card")!!.category);assertEquals(6,accountPreset("lend")!!.category);assertEquals(5,accountPreset("borrow")!!.category)
        assertNull(accountPreset("unknown"))
    }
}
