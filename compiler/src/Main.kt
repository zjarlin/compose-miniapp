package compose.miniapp.compiler

import java.io.File

// ---------- CLI 入口 ----------

fun main(args: Array<String>) {
    val srcDir = File(args.getOrElse(0) { "sample-app/src/pages" })
    val outDir = File(args.getOrElse(1) { "out/miniapp" })
    val extraDir = File(args.getOrElse(2) { "miniApp/src/miniAppMain" })

    if (!srcDir.isDirectory) {
        System.err.println("错误：页面目录不存在：$srcDir")
        kotlin.system.exitProcess(2)
    }

    val files: List<File> = srcDir.listFiles { f -> f.isFile && f.extension == "kt" }
        ?.sortedBy { it.name }
        ?: emptyList()

    if (files.isEmpty()) {
        System.err.println("错误：页面目录中没有 .kt 文件：$srcDir")
        kotlin.system.exitProcess(2)
    }

    val artifacts = mutableListOf<PageArtifact>()
    for (f in files) {
        val src = f.readText()
        val file = Parser(src, f.name).parseFile()
        val tree = Analyzer.analyze(file)
        artifacts.add(
            PageArtifact(
                tree = tree,
                wxml = WxmlGenerator.generate(tree),
                wxss = WxssGenerator.generate(tree),
                js = JsGenerator.generate(tree),
                json = JsonGenerator.page(tree),
            )
        )
        println("✔  ${f.name} -> ${tree.pagePath}")
    }

    Assembler.assemble(artifacts, outDir, if (extraDir.isDirectory) extraDir else null)

    println()
    println("产物目录：${outDir.absolutePath}")
    println("页面数：${artifacts.size}")
    println("导入方式：微信开发者工具 → 导入项目 → 选择 $outDir")
}
