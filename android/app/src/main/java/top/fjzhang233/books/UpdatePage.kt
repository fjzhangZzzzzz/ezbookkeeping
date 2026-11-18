package top.fjzhang233.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable fun UpdatePage(vm: UpdateModel) {
    LazyColumn(contentPadding=PaddingValues(16.dp,8.dp,16.dp,32.dp),verticalArrangement=Arrangement.spacedBy(24.dp),modifier=Modifier.testTag("about-update-page")) {
        item {
            SettingsSection("版本") {
                SettingsRow(Icons.Outlined.Info,"家庭账本",supporting="当前版本 ${BuildConfig.VERSION_NAME}",testTag="about-current-version")
                SettingsDivider()
                SettingsRow(Icons.Outlined.Download,"检查更新",supporting=if(vm.checking)"正在检查最新版本" else "仅手动检查，不会后台打扰",onClick={if(!vm.checking&&!vm.downloading)vm.check(true)},trailing={Text(if(vm.checking)"检查中…" else "检查",color=MaterialTheme.colorScheme.primary)},testTag="about-check-update")
            }
        }
        if(vm.checking)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        if(vm.message.isNotBlank())item{Text(vm.message,style=MaterialTheme.typography.bodyMedium,color=ledgerColors.muted)}
        vm.release?.let { update ->
            item {
                Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=ledgerColors.surface) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("发现新版本 ${update.versionName}",style=MaterialTheme.typography.titleLarge)
                        Text("下载完成后仍需由 Android 系统确认安装。",style=MaterialTheme.typography.bodyMedium,color=ledgerColors.muted)
                        when {
                            vm.downloading -> { LinearProgressIndicator(progress={vm.progress},modifier=Modifier.fillMaxWidth()); Text("下载中 ${(vm.progress*100).toInt()}%"); OutlinedButton(onClick=vm::cancel) { Text("取消下载") } }
                            vm.ready -> Button(onClick=vm::install,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("安装更新") }
                            else -> Button(onClick=vm::download,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("下载更新") }
                        }
                    }
                }
            }
        }
    }
}
