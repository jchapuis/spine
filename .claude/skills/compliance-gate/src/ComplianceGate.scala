package compliancegate

import mainargs.{Flag, ParserForMethods, arg, main}

import GateError.*

object ComplianceGate:

  @main
  def run(
      @arg(doc = "Hook mode: pre-commit | pre-push") hook: Option[String] = None,
      @arg(doc = "Project root (default: current working directory)") projectRoot: Option[String] = None,
      @arg(doc = "Diff source: staged | workdir | branch") diff: Option[String] = None,
      @arg(doc = "Base ref override") base: Option[String] = None,
      @arg(doc = "Limit to these skill names (comma-separated)") skills: Option[String] = None,
      @arg(doc = "Skills root holding compliance-gate/SKILL.md (else env SPINE_SKILLS_ROOT, which bin/compliance-gate defaults to its own skills directory)")
      skillsRoot: Option[String] = None,
      @arg(doc = "Emit JSON to stdout instead of human-readable to stderr") json: Flag = Flag(),
      @arg(doc = "Skip scalafmt check") noScalafmt: Flag = Flag(),
      @arg(doc = "Skip scalafix check") noScalafix: Flag = Flag(),
      @arg(doc = "Run self-test: validate frontmatter and fixtures") selfTest: Flag = Flag()
  ): Unit =
    val options = GateOptions(
      roots = GateRoots(projectRoot, skillsRoot),
      selection = DiffSelection(hook, diff, base),
      skills = skills,
      json = json.value,
      runScalafmt = !noScalafmt.value,
      runScalafix = !noScalafix.value,
      selfTest = selfTest.value
    )
    sys.exit(performGate(options))

  private def performGate(options: GateOptions) =
    if sys.env.get("SPINE_GATE_DISABLE").contains("1") then
      System.err.println("compliance-gate: disabled (SPINE_GATE_DISABLE=1)")
      ExitCodes.Disabled
    else
      val root = options.roots.projectRoot.map(os.Path(_, os.pwd)).getOrElse(os.pwd)
      SkillsRoot.resolve(options.roots.skillsRoot.map(os.Path(_, os.pwd))) match
        case Left(err) =>
          System.err.println(s"compliance-gate: ${err.display}")
          ExitCodes.ToolError
        case Right(skillsRootResolved) =>
          if options.selfTest then runSelfTest(skillsRootResolved, skillsRootResolved / "compliance-gate" / "fixtures")
          else evaluate(options, root, skillsRootResolved)

  private def evaluate(options: GateOptions, root: os.Path, skillsRootResolved: os.Path) =
    val prepared =
      for
        skills <- selectSkills(skillsRootResolved, options.skills).left.map(_.display)
        source <- resolveDiffSource(options.selection, root)
      yield (skills = skills, source = source)
    prepared match
      case Left(message) =>
        System.err.println(s"compliance-gate: $message")
        ExitCodes.ToolError
      case Right(ready) => evaluateDiff(options, root, skillsRootResolved, ready.skills, ready.source)

  private def selectSkills(skillsRoot: os.Path, requested: Option[String]) =
    val allSkills = Frontmatter
      .loadSkills(skillsRoot)
      .filter:
        case (_, skill) => skill.name != "compliance-gate"
    val allNames = allSkills.map((_, skill) => skill.name)
    val requestedNames = requested.map(_.split(",").map(_.trim).filter(_.nonEmpty).toList)
    val unknown = requestedNames.toList.flatten.filterNot(allNames.contains)
    if !allSkills.exists((_, skill) => skill.mechanicalChecks.exists(_.nonEmpty)) then
      Left(NoRuleBearingSkills(skillsRoot))
    else if unknown.nonEmpty then Left(UnknownSkillNames(unknown, allNames.sorted))
    else
      Right:
        requestedNames.fold(allSkills): names =>
          allSkills.filter((_, skill) => names.contains(skill.name))

  private def evaluateDiff(
      options: GateOptions,
      root: os.Path,
      skillsRootResolved: os.Path,
      skills: List[(os.Path, SkillFrontmatter)],
      source: DiffSource
  ) =
    Diff.changedFiles(root, source) match
      case Left(error) =>
        System.err.println(s"compliance-gate: ${error.display}")
        ExitCodes.ToolError
      case Right(files) => evaluateFiles(options, root, skillsRootResolved, skills, files.filter(file => file.isCode))

  private def evaluateFiles(
      options: GateOptions,
      root: os.Path,
      skillsRootResolved: os.Path,
      limited: List[(os.Path, SkillFrontmatter)],
      changed: List[ChangedFile]
  ) =
    if changed.isEmpty then
      if !options.json then System.err.println("compliance-gate: no Scala / Python staged — fast path")
      ExitCodes.Clean
    else
      val context = ProjectContext.build(root, changed)
      val runtime = Runtime(
        files = changed,
        runScalafmt = options.runScalafmt,
        runScalafix = options.runScalafix,
        skillsRoot = skillsRootResolved
      )
      val findings = Rules.run(limited, context, runtime)
      emitOutput(findings, options.json)
      if findings.exists(_.severity.blocks) then ExitCodes.Findings else ExitCodes.Clean

  private def resolveDiffSource(selection: DiffSelection, projectRoot: os.Path) =
    def branch = DiffSource.Branch(Diff.resolveBase(projectRoot, selection.base).getOrElse("HEAD~50"))
    (selection.hook, selection.diff) match
      case (Some("pre-commit"), _) => Right(DiffSource.Staged)
      case (Some("pre-push"), _)   => Right(branch)
      case (Some(unknown), _)      => Left(s"unknown --hook '$unknown' — expected: pre-commit | pre-push")
      case (None, None)            => Right(DiffSource.Staged)
      case (None, Some("staged"))  => Right(DiffSource.Staged)
      case (None, Some("workdir")) => Right(DiffSource.Workdir)
      case (None, Some("branch"))  => Right(branch)
      case (None, Some(unknown))   => Left(s"unknown --diff '$unknown' — expected: staged | workdir | branch")

  private def emitOutput(findings: List[Finding], jsonOutput: Boolean) =
    if jsonOutput then println(Report.json(findings))
    else System.err.println(Report.human(findings))

  private def runSelfTest(skillsRoot: os.Path, fixturesRoot: os.Path) =
    val result = SelfTest.run(skillsRoot, fixturesRoot)
    if result.isClean then
      System.err.println(s"compliance-gate: self-test passed (${result.passed} checks)")
      ExitCodes.Clean
    else
      result.failed.foreach(line => System.err.println(s"compliance-gate: $line"))
      System.err.println(s"compliance-gate: self-test failed (${result.failed.size} issues)")
      ExitCodes.ToolError

  def main(args: Array[String]): Unit =
    ParserForMethods(this).runOrThrow(args)

object ExitCodes:
  val Clean = 0
  val Findings = 1
  val ToolError = 2
  val Disabled = 3
