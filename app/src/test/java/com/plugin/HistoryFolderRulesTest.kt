package com.plugin

import com.plugin.compatibility.MediaRules
import org.junit.Assert.*
import org.junit.Test

/** 같은 화질 이름·문서 URI·상위 이동의 폴더 탐색 경계를 검증합니다. */
class HistoryFolderRulesTest {
    /** 웹사이트별 720p를 서로 다른 경로의 직접 자식으로 탐색합니다. */
    @Test
    fun sameNamesKeepTheirHierarchy() {
        val root = "/storage/emulated/0/Download/Seal"
        val folders = listOf("$root/YouTube/720p", "$root/TikTok/720p", "$root")
        assertEquals(root, MediaRules.historyFolderRoot(root, folders))
        assertEquals(listOf("$root/TikTok", "$root/YouTube"), MediaRules.historyFolderChildren(root, folders))
        assertEquals(listOf("$root/YouTube/720p"), MediaRules.historyFolderChildren("$root/YouTube", folders))
        assertEquals(emptyList<String>(), MediaRules.historyFolderChildren("$root/YouTube/720p", folders))
        assertEquals("$root/YouTube", MediaRules.historyFolderParent("$root/YouTube/720p", root))
        assertEquals(root, MediaRules.historyFolderParent(root, root))
        assertFalse(MediaRules.historyFolderChildren(root, listOf("${root}Other/720p")).isNotEmpty())
    }

    /** 문서 경로의 더하기 기호와 서로 다른 저장소의 가상 루트를 보존합니다. */
    @Test
    fun documentPathsAndDifferentRootsRemainReachable() {
        val uri = "content://provider/tree/primary%3ADownload/document/primary%3ADownload%2FA+B%2F720p%2Fx.png"
        val folder = MediaRules.parentFolder(uri)
        assertTrue(folder.endsWith("A+B/720p"))
        val documentRoot = folder.substringBeforeLast('/')
        assertEquals(documentRoot, MediaRules.historyFolderRoot(documentRoot, listOf(folder)))
        assertEquals(listOf(folder), MediaRules.historyFolderChildren(documentRoot, listOf(folder)))
        assertEquals("/", MediaRules.historyFolderRoot("/storage/emulated/0/Download", listOf("/data/user/0/app/files")))
        assertEquals("/", MediaRules.historyFolderParent("/storage", "/"))
        val roots = MediaRules.historyFolderChildren("", listOf(folder, "/storage/emulated/0/Download"))
        assertEquals("", MediaRules.historyFolderRoot("content://other/tree/b/document/b", listOf(folder)))
        assertEquals("content://provider/document/bad%", MediaRules.parentFolder("content://provider/document/bad%/x.png"))
        assertEquals(2, roots.size)
        assertEquals("", MediaRules.historyFolderParent(roots.first { it.startsWith("content://") }, ""))
    }
}
