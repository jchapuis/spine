package compliancegate

case class RuleContext(
    skill: SkillFrontmatter,
    check: MechanicalCheck,
    projectRoot: os.Path,
    files: List[ChangedFile],
    fileHeads: Map[os.RelPath, String]
):
  def relevantFiles: List[ChangedFile] =
    files.filter(file => file.isCode && file.status != FileStatus.Deleted && matchesPaths(file))

  def importGated(file: ChangedFile): Boolean =
    check.filesMustImport.isEmpty || ImportMatch.anyMatches(check.filesMustImport, fileHeads.getOrElse(file.path, ""))

  def makeFinding(
      file: String,
      line: Int,
      column: Option[Int],
      message: String = check.message,
      fixable: Boolean = false
  ): Finding =
    Finding(
      file = file,
      line = line,
      column = column,
      skill = skill.name,
      rule = check.id,
      severity = check.resolvedSeverity,
      message = message,
      tool = check.tool,
      fixable = fixable,
      references = check.references
    )

  private def matchesPaths(file: ChangedFile) =
    val included = check.paths.isEmpty || check.paths.exists(Glob.matches(_, file.path))
    val excluded = check.pathsExclude.exists(Glob.matches(_, file.path))
    included && !excluded

object Rules:
  private val GlobalExcludes = List(
    "**/compliance-gate/fixtures/**",
    "**/.claude/skills/compliance-gate/fixtures/**",
    "**/compliance-gate/rules/**",
    "**/.claude/skills/compliance-gate/rules/**"
  )

  def run(skills: List[(os.Path, SkillFrontmatter)], context: ProjectContext, runtime: Runtime): List[Finding] =
    val filteredFiles = runtime.files.filterNot: file =>
      GlobalExcludes.exists(glob => Glob.matches(glob, file.path))
    val effectiveRuntime = runtime.copy(files = filteredFiles)
    val applicableSkills = skills.filter:
      case (_, skill) => Triggers.applicable(skill, filteredFiles, context)
    val activeChecks = applicableSkills.flatMap:
      case (_, skill) => skill.mechanicalChecks.getOrElse(List.empty).map(check => skill -> check)
    val (scalafixChecks, otherChecks) = activeChecks.partition((_, check) => check.tool == "scalafix")
    val nonScalafixFindings = otherChecks.flatMap:
      case (skill, check) =>
        val ruleContext = RuleContext(skill, check, context.root, filteredFiles, context.fileHeads)
        runNonScalafix(ruleContext, effectiveRuntime)
    val scalafixFindings =
      if effectiveRuntime.runScalafix && scalafixChecks.nonEmpty then
        Scalafix.runBatch(scalafixChecks, context, filteredFiles, runtime.skillsRoot)
      else List.empty
    nonScalafixFindings ++ scalafixFindings

  private def runNonScalafix(ruleContext: RuleContext, runtime: Runtime) = ruleContext.check.tool match
    case "ripgrep"  => Ripgrep.run(ruleContext)
    case "scalafmt" => if runtime.runScalafmt then Scalafmt.run(ruleContext) else List.empty
    case unknown    =>
      System.err.println(s"compliance-gate: unknown tool '$unknown' for rule ${ruleContext.check.id}")
      List.empty

case class Runtime(
    files: List[ChangedFile],
    runScalafmt: Boolean,
    runScalafix: Boolean,
    skillsRoot: os.Path
)

object Ripgrep:
  def run(ruleContext: RuleContext): List[Finding] =
    val targets = ruleContext.relevantFiles.filter(ruleContext.importGated)
    if targets.isEmpty then List.empty
    else
      val pattern = ruleContext.check.pattern.getOrElse:
        sys.error(s"ripgrep rule ${ruleContext.check.id} has no pattern")
      val rawHits = targets.flatMap(file => scanFile(ruleContext, file, pattern))
      ruleContext.check.aggregate match
        case Some("by_value")             => aggregateByValue(ruleContext, rawHits)
        case Some("by_import_block_hash") => aggregateByImportBlock(ruleContext)
        case _                            => rawHits.map(hit => ruleContext.makeFinding(hit.file, hit.line, Some(hit.column)))

  private def scanFile(ruleContext: RuleContext, file: ChangedFile, pattern: String) =
    val absolute = ruleContext.projectRoot / file.path
    if !os.exists(absolute) then List.empty
    else if ruleContext.check.multiline then scanMultilineMatches(ruleContext, file, pattern, absolute)
    else scanLineMatches(ruleContext, file, pattern, absolute)

  private def scanLineMatches(ruleContext: RuleContext, file: ChangedFile, pattern: String, absolute: os.Path) =
    val result = os
      .proc("rg", "--no-heading", "--no-filename", "--line-number", "--column", "-P", pattern, absolute.toString)
      .call(cwd = ruleContext.projectRoot, check = false, stderr = os.Pipe)
    if result.exitCode > 1 then
      System.err.println(s"compliance-gate: ripgrep error on ${file.path}: ${result.err.text()}")
      List.empty
    else result.out.text().linesIterator.toList.flatMap(line => parseRgLine(file.path.toString, line))

  private def scanMultilineMatches(ruleContext: RuleContext, file: ChangedFile, pattern: String, absolute: os.Path) =
    val result = os
      .proc("rg", "--json", "-U", "--multiline-dotall", "-P", pattern, absolute.toString)
      .call(cwd = ruleContext.projectRoot, check = false, stderr = os.Pipe)
    if result.exitCode > 1 then
      System.err.println(s"compliance-gate: ripgrep error on ${file.path}: ${result.err.text()}")
      List.empty
    else
      result.out
        .text()
        .linesIterator
        .toList
        .flatMap(line => parseRgJson(file.path.toString, line))

  private def parseRgJson(filePath: String, line: String) =
    if line.isEmpty then None
    else
      scala.util.Try(ujson.read(line)).toOption.flatMap: json =>
        json.obj.get("type").flatMap(_.strOpt) match
          case Some("match") =>
            val data = json("data")
            for
              lineNumber <- data.obj.get("line_number").flatMap(_.numOpt).map(_.toInt)
              column     <- data.obj.get("submatches").flatMap(_.arrOpt).flatMap(_.headOption)
                              .flatMap(_.obj.get("start")).flatMap(_.numOpt).map(_.toInt + 1)
              text       <- data.obj.get("lines").flatMap(_.obj.get("text")).flatMap(_.strOpt)
            yield RipgrepHit(filePath, lineNumber, column, text)
          case _ => None

  private def parseRgLine(filePath: String, line: String) =
    line.split(":", 3).toList match
      case lineField :: columnField :: matched :: Nil =>
        val hit =
          for
            lineNumber <- lineField.toIntOption
            column     <- columnField.toIntOption
          yield RipgrepHit(filePath, lineNumber, column, matched)
        hit.orElse(reportUnreadable(filePath, line))
      case _ => reportUnreadable(filePath, line)

  private def reportUnreadable(filePath: String, line: String) =
    System.err.println(s"compliance-gate: unreadable ripgrep output for $filePath — match not reported: $line")
    None

  private def aggregateByValue(ruleContext: RuleContext, hits: List[RipgrepHit]) =
    val threshold = ruleContext.check.threshold.getOrElse(3)
    hits.groupBy(_.value.trim).toList.flatMap:
      case (value, occurrences) if occurrences.sizeIs >= threshold =>
        occurrences.map: hit =>
          ruleContext.makeFinding(
            hit.file,
            hit.line,
            Some(hit.column),
            message = s"${ruleContext.check.message} (value `$value` repeats ${occurrences.size}×)"
          )
      case _ => List.empty

  private def aggregateByImportBlock(ruleContext: RuleContext) =
    val threshold = ruleContext.check.threshold.getOrElse(2)
    val minimumBlockSize = 3
    val importBlocks = ruleContext.relevantFiles.flatMap: file =>
      val head = ruleContext.fileHeads.getOrElse(file.path, "")
      val imports = head.linesIterator.filter(_.trim.startsWith("import ")).toList.sorted
      Option.when(imports.sizeIs >= minimumBlockSize)(imports.mkString("\n") -> file)
    importBlocks.groupMap((block, _) => block)((_, file) => file).toList.flatMap:
      case (block, files) if files.sizeIs >= threshold =>
        val importCount = block.linesIterator.size
        files.map: file =>
          ruleContext.makeFinding(
            file.path.toString,
            line = 1,
            column = None,
            message = s"${ruleContext.check.message} (${files.size} files share an identical $importCount-line import block)"
          )
      case _ => List.empty

private case class RipgrepHit(file: String, line: Int, column: Int, value: String)

object Scalafmt:
  def run(ruleContext: RuleContext) =
    val configName = os.RelPath(ruleContext.check.config.getOrElse(".scalafmt.conf"))
    val targets = ruleContext.relevantFiles.filter(file => file.path.toString.endsWith(".scala"))
    val (orphans, governed) = targets
      .map(file => nearestConfig(ruleContext.projectRoot, file, configName) -> file)
      .partitionMap:
        case (Some(config), file) => Right(config -> file)
        case (None, file)         => Left(file)
    if orphans.nonEmpty then
      val paths = orphans.map(_.path).mkString(", ")
      System.err.println(
        s"compliance-gate: skipped ${ruleContext.check.id} for ${orphans.size} file(s) — no $configName in any ancestor directory: $paths"
      )
    governed
      .groupMap((config, _) => config)((_, file) => file)
      .toList
      .flatMap((config, files) => checkGroup(ruleContext, config, files))

  private def nearestConfig(projectRoot: os.Path, file: ChangedFile, configName: os.RelPath) =
    Iterator
      .iterate(projectRoot / file.path / os.up)(_ / os.up)
      .takeWhile(_.startsWith(projectRoot))
      .map(_ / configName)
      .find(os.exists)

  private def checkGroup(ruleContext: RuleContext, config: os.Path, files: List[ChangedFile]) =
    val command = List("scalafmt", "--list", "--config", config.toString) ++ files.map(file =>
      (ruleContext.projectRoot / file.path).toString
    )
    val result = os.proc(command).call(cwd = ruleContext.projectRoot, check = false, stderr = os.Pipe)
    val output = result.out.text() ++ result.err.text()
    val misformatted = output.linesIterator.toList.flatMap(extractScalafmtFile).distinct
    if misformatted.nonEmpty then
      misformatted.map(filePath => ruleContext.makeFinding(filePath, line = 1, column = None, fixable = true))
    else if result.exitCode != 0 then
      List(ruleContext.makeFinding("(scalafmt)", line = 1, column = None, fixable = true))
    else
      List.empty

  private val DiffPathPattern = """^(?:---|\+\+\+)\s+[ab]/(.+\.scala)\s*$""".r
  private val ListPattern = """^(/?[^\s]+\.scala)\s*$""".r
  private val IsBadPattern = """\[error\]\s+(.+?\.scala).*""".r
  private val MisformattedPattern = """.*--test failed.*?([^\s]+\.scala).*""".r
  private def extractScalafmtFile(line: String) = line match
    case DiffPathPattern(file)     => Some(if file.startsWith("/") then file else s"/$file")
    case ListPattern(file)         => Some(file)
    case IsBadPattern(file)        => Some(file)
    case MisformattedPattern(file) => Some(file)
    case _                         => None

object Scalafix:
  private val OutputPattern = "^(.+\\.scala):(\\d+):(\\d+): (?:error|warning|info): \\[([^\\]]+)\\] (.+)$".r
  private val DisableSyntaxPrefix = "DisableSyntax."

  def runBatch(
      checks: List[(SkillFrontmatter, MechanicalCheck)],
      context: ProjectContext,
      files: List[ChangedFile],
      skillsRoot: os.Path
  ): List[Finding] =
    val targets = files.filter(file => file.isScala && file.status != FileStatus.Deleted)
    if targets.isEmpty then List.empty
    else
      locateLauncher() match
        case None =>
          System.err.println(
            "compliance-gate: skipped scalafix rules — coursier (`cs`/`coursier`) not on PATH; install from https://get-coursier.io"
          )
          List.empty
        case Some(launcher) => invoke(launcher, checks, context, targets, skillsRoot)

  private def invoke(
      launcher: String,
      checks: List[(SkillFrontmatter, MechanicalCheck)],
      context: ProjectContext,
      targets: List[ChangedFile],
      skillsRoot: os.Path
  ) =
    val customRuleDir = skillsRoot / "compliance-gate" / "rules" / "scalafix"
    val configFile = os.temp(generateConfig(checks.map((_, check) => check), customRuleDir), suffix = ".scalafix.conf")
    val absoluteFiles = targets.map(file => (context.root / file.path).toString)
    val command = List(launcher, "launch", "scalafix", "--", "--check", "--config", configFile.toString) ++ absoluteFiles
    val result = os.proc(command).call(cwd = context.root, check = false, stderr = os.Pipe, mergeErrIntoOut = false)
    if sys.env.contains("SPINE_GATE_DEBUG") then
      System.err.println(s"compliance-gate[debug] cmd: ${command.mkString(" ")}")
      System.err.println(s"compliance-gate[debug] config:\n${os.read(configFile)}")
      System.err.println(s"compliance-gate[debug] exit: ${result.exitCode}")
      System.err.println(s"compliance-gate[debug] stdout: ${result.out.text()}")
      System.err.println(s"compliance-gate[debug] stderr: ${result.err.text()}")
    parseOutput(checks, context, result.err.text() ++ result.out.text())

  private def locateLauncher() =
    List("cs", "coursier").find: candidate =>
      os.proc("sh", "-c", s"command -v $candidate").call(check = false).exitCode == 0

  private def generateConfig(checks: List[MechanicalCheck], customRuleDir: os.Path) =
    val rules = checks.flatMap(_.rule).distinct
    val (disableSyntaxRules, otherRules) = rules.partition(_.startsWith(DisableSyntaxPrefix))
    val customRuleEntries = otherRules.distinct.map: ruleName =>
      val candidate = customRuleDir / s"$ruleName.scala"
      if os.exists(candidate) then s"\"file:$candidate\""
      else ruleName
    val ruleNames =
      (if disableSyntaxRules.nonEmpty then List("DisableSyntax") else List.empty) ++ customRuleEntries
    val ruleList = s"rules = [${ruleNames.mkString(", ")}]"
    val enabledFlags = disableSyntaxRules.distinct.map(name => s"$name = true").mkString("\n")
    List(ruleList, enabledFlags).filter(_.nonEmpty).mkString("\n") + "\n"

  private val ShortToFlag = Map(
    "throw"            -> "noThrows",
    "asInstanceOf"     -> "noAsInstanceOf",
    "isInstanceOf"     -> "noIsInstanceOf",
    "var"              -> "noVars",
    "null"             -> "noNulls",
    "return"           -> "noReturns",
    "while"            -> "noWhileLoops",
    "finalize"         -> "noFinalize",
    "default-args"     -> "noDefaultArgs",
    "covariant-types"  -> "noCovariantTypes",
    "contra-types"     -> "noContravariantTypes",
    "universal-equality" -> "noUniversalEquality"
  )

  private def parseOutput(
      checks: List[(SkillFrontmatter, MechanicalCheck)],
      context: ProjectContext,
      output: String
  ) =
    val byRule =
      checks
        .flatMap((skill, check) => check.rule.map(rule => rule -> (skill, check)))
        .groupMap((rule, _) => rule)((_, binding) => binding)
    output.linesIterator.toList.flatMap:
      case OutputPattern(file, lineField, columnField, ruleTag, message) =>
        val canonicalRule = normalizeRuleName(ruleTag)
        byRule.getOrElse(canonicalRule, List.empty).flatMap: pair =>
          val (skill, check) = pair
          for
            line   <- lineField.toIntOption
            column <- columnField.toIntOption
            if importGatedForFile(check, file, context)
          yield makeFinding(skill, check, file, line, Some(column), message.trim)
      case _ => List.empty

  private def normalizeRuleName(raw: String) =
    if raw.startsWith(DisableSyntaxPrefix) then
      val tail = raw.stripPrefix(DisableSyntaxPrefix)
      val flag = ShortToFlag.getOrElse(tail, tail)
      s"$DisableSyntaxPrefix$flag"
    else raw.takeWhile(_ != '.')

  private def importGatedForFile(check: MechanicalCheck, file: String, context: ProjectContext) =
    if check.filesMustImport.isEmpty then true
    else
      val rootPath = context.root.toString
      val relativeString =
        if file.startsWith(rootPath + "/") then file.stripPrefix(rootPath + "/")
        else file
      ImportMatch.anyMatches(check.filesMustImport, context.fileHeads.getOrElse(os.RelPath(relativeString), ""))

  private def makeFinding(
      skill: SkillFrontmatter,
      check: MechanicalCheck,
      file: String,
      line: Int,
      column: Option[Int],
      message: String
  ) =
    Finding(
      file = file,
      line = line,
      column = column,
      skill = skill.name,
      rule = check.id,
      severity = check.resolvedSeverity,
      message = message,
      tool = check.tool,
      fixable = false,
      references = check.references
    )
