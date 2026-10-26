package com.continuousauth.ui.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared by the first-use notice and the consent section, keeping both descriptions consistent. */
@Composable
fun PrivacyNoticeContent(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NoticeParagraph("会采集什么", "在你同意并开始采集后，应用会记录手机的移动、转动和磁场变化，以及设备型号、系统版本和当时使用的应用。你可以分别关闭三种传感器的采集。")
        NoticeParagraph("用来做什么", "这些数据用于持续身份认证研究，帮助判断手机的使用方式是否与本人相符，并用于改进认证和统计分析。认证结果可能受握持姿势、活动和环境影响，不通过并不一定表示他人在使用。")
        NoticeParagraph("数据会存在哪里", "传感器数据会加密后暂存在本机，并上传到“服务器设置”中指定的服务器用于认证。传输是否使用加密连接取决于服务器配置和实际连接情况，可在“详细信息”查看。服务器保存的数据按其研究和管理规则处理。")
        NoticeParagraph("保存多久", "本机保存最近 7 天的认证结果及发生时使用的应用，过期记录会自动清理。待上传的传感器缓存另按“数据与存储”设置的期限清理。")
        NoticeParagraph("你可以随时决定", "不同意也可以浏览页面和调整设置，数据采集和认证需要先获得你的同意。之后可以在这里撤回同意，停止采集并删除本应用内的数据。当前服务器尚未提供自动删除接口，已上传的数据仍需联系服务器管理方删除；这里会显示为待处理。你手动导出的副本需自行保管和删除。")
    }
}

@Composable
private fun NoticeParagraph(title: String, message: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
