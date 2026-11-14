package top.fjzhang233.books

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun UpdatePage(vm: UpdateModel) {
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        Text("家庭账本",style=MaterialTheme.typography.headlineMedium)
        Text("原生 Android · ${BuildConfig.VERSION_NAME}")
        Text("独立账户，家庭共享。使用现有家庭账本服务。")
        Text("更新来源：GitHub · ${UpdatePolicy.REPOSITORY}\n通道：正式 Android 版本",style=MaterialTheme.typography.bodySmall)
        Button(onClick={vm.check(true)},enabled=!vm.checking&&!vm.downloading,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text(if(vm.checking) "检查中…" else "检查 GitHub 更新") }
        if(vm.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(vm.message.isNotBlank()) Text(vm.message)
        vm.release?.let { update ->
            Text("新版本 ${update.versionName}",style=MaterialTheme.typography.titleLarge)
            Text("确认后开始下载，校验签名后仍需由 Android 系统确认安装。")
            when {
                vm.downloading -> { LinearProgressIndicator(progress={vm.progress},modifier=Modifier.fillMaxWidth()); Text("下载中 ${(vm.progress*100).toInt()}%"); OutlinedButton(onClick=vm::cancel) { Text("取消下载") } }
                vm.ready -> Button(onClick=vm::install) { Text("交给系统安装") }
                else -> Button(onClick=vm::download,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text("确认下载 ${update.versionName}") }
            }
        }
        if(BuildConfig.DEBUG) Text("调试版与正式版签名不同，不能直接覆盖；签名检查始终启用。",style=MaterialTheme.typography.bodySmall)
    }
}
