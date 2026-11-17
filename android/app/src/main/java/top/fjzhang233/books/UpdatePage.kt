package top.fjzhang233.books

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun UpdatePage(vm: UpdateModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("家庭账本",style=MaterialTheme.typography.headlineMedium)
        Text("当前版本 ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.bodyLarge)
        Button(onClick={vm.check(true)},enabled=!vm.checking&&!vm.downloading,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text(if(vm.checking) "检查中…" else "检查更新") }
        if(vm.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(vm.message.isNotBlank()) Text(vm.message,style=MaterialTheme.typography.bodyMedium)
        vm.release?.let { update ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("发现新版本 ${update.versionName}",style=MaterialTheme.typography.titleLarge)
                    Text("下载完成后仍需由 Android 系统确认安装。",style=MaterialTheme.typography.bodyMedium)
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
