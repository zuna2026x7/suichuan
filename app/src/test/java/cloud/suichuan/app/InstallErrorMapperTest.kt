package cloud.suichuan.app

import cloud.suichuan.app.util.InstallErrorMapper
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallErrorMapperTest {

    @Test
    fun missingSplitIsExplained() {
        val msg = InstallErrorMapper.messageFor("INSTALL_FAILED_MISSING_SPLIT: Missing split for com.example")
        assertTrue(msg.contains("不完整"))
        assertTrue(msg.contains("重新发送"))
    }

    @Test
    fun signatureConflictIsExplained() {
        val msg = InstallErrorMapper.messageFor("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package signatures do not match")
        assertTrue(msg.contains("来源不同"))
        assertTrue(msg.contains("卸载"))
    }

    @Test
    fun downgradeIsExplained() {
        val msg = InstallErrorMapper.messageFor("INSTALL_FAILED_VERSION_DOWNGRADE")
        assertTrue(msg.contains("降级"))
    }

    @Test
    fun storageIsExplained() {
        val msg = InstallErrorMapper.messageFor("INSTALL_FAILED_INSUFFICIENT_STORAGE")
        assertTrue(msg.contains("空间不足"))
    }

    @Test
    fun parseFailureIsExplained() {
        val msg = InstallErrorMapper.messageFor("INSTALL_PARSE_FAILED_NOT_APK")
        assertTrue(msg.contains("损坏"))
    }

    @Test
    fun blankAndUnknownFallBackGracefully() {
        assertTrue(InstallErrorMapper.messageFor(null).contains("安装没有成功"))
        assertTrue(InstallErrorMapper.messageFor("").contains("安装没有成功"))
        val unknown = InstallErrorMapper.messageFor("INSTALL_FAILED_SOMETHING_NEW")
        assertTrue(unknown.contains("安装没有成功"))
        assertTrue(unknown.contains("INSTALL_FAILED_SOMETHING_NEW"))
    }
}
