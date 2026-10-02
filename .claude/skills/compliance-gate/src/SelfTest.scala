package compliancegate

import GateError.*

object SelfTest:
  case class Result(passed: Int, failed: List[String]):
    def isClean: Boolean = failed.isEmpty

  def run(skillsRoot: os.Path, fixturesRoot: os.Path): Result =
    val loaded = Frontmatter.loadAll(skillsRoot)
    val skills = loaded.skills.filter:
      case (_, skill) => skill.name != "compliance-gate"
    val loadErrors = loaded.errors.map(_.display)
    val schemaErrors = skills.flatMap:
      case (path, skill) => validateSchema(path, skill)
    val checks = skills.flatMap((_, skill) => skill.mechanicalChecks.getOrElse(List.empty))
    val ruleSetResults = List(
      "rule-set/non-empty" -> checks.nonEmpty,
      "fixtures/present"   -> os.isDir(fixturesRoot)
    )
    val fixtureResults = if os.isDir(fixturesRoot) then runAllFixtures(checks, fixturesRoot) else List.empty
    val allResults = ruleSetResults ++ skillsRootResults(skillsRoot) ++ fixtureResults ++ diffParserResults
    val passed = allResults.count((_, succeeded) => succeeded)
    val failures = loadErrors ++ schemaErrors ++ allResults.collect:
      case (label, false) => label
    Result(passed, failures)

  private def skillsRootResults(skillsRoot: os.Path) =
    val personalSkillsLookalike = os.temp.dir()
    os.makeDir.all(personalSkillsLookalike / "jonas")
    os.write(personalSkillsLookalike / "jonas" / "SKILL.md", "---\nname: jonas\ndescription: voice\n---\n")
    val expectations = List(
      "skills-root/accepts-gate-root"          -> SkillsRoot.validate(skillsRoot, "self-test").isRight,
      "skills-root/rejects-skills-without-gate" -> SkillsRoot.validate(personalSkillsLookalike, "self-test").isLeft,
      "skills-root/rejects-gate-dir-itself"     -> SkillsRoot.validate(skillsRoot / "compliance-gate", "self-test").isLeft
    )
    os.remove.all(personalSkillsLookalike)
    expectations

  private def diffParserResults =
    val expectations = List(
      "M\tapp/Main.scala"                       -> Some("app/Main.scala" -> FileStatus.Modified),
      "A\tapp/New.scala"                        -> Some("app/New.scala" -> FileStatus.Added),
      "D\tapp/Gone.scala"                       -> Some("app/Gone.scala" -> FileStatus.Deleted),
      "R090\told/Moved.scala\tnew/Moved.scala" -> Some("new/Moved.scala" -> FileStatus.Modified),
      "C075\told/Copied.scala\tnew/Copy.scala" -> Some("new/Copy.scala" -> FileStatus.Modified),
      "M\tapp/ui components/Main.scala"         -> Some("app/ui components/Main.scala" -> FileStatus.Modified),
      "M app/Main.scala"                         -> None,
      "R090"                                     -> None
    )
    expectations.map: (line, expected) =>
      val parsed = Diff.parseNameStatusLine(line).toOption.map(file => file.path.toString -> file.status)
      s"diff-parse/${line.replace("\t", "<TAB>")}" -> (parsed == expected)

  private def validateSchema(path: os.Path, skill: SkillFrontmatter) =
    val checks = skill.mechanicalChecks.getOrElse(List.empty)
    val unknownTool = checks.filter(check => !Set("ripgrep", "scalafmt", "scalafix").contains(check.tool))
    val missingPattern = checks.filter(check => check.tool == "ripgrep" && check.pattern.isEmpty)
    val missingRule = checks.filter(check => check.tool == "scalafix" && check.rule.isEmpty)
    val severityErrors = checks.flatMap: check =>
      check.severity.flatMap(value => Severity.parse(value).left.toOption)
    val triggerless = skill.triggers match
      case None | Some(Nil) if checks.nonEmpty =>
        List(s"$path: declares mechanical_checks but no triggers block — the checks can never fire")
      case _ => List.empty
    val ruleIdShape = checks.filter(check => !RuleIdPattern.matches(check.id))
    triggerless
      ++ unknownTool.map(check => s"$path: rule ${check.id} has unknown tool '${check.tool}'")
      ++ missingPattern.map(check => s"$path: ripgrep rule ${check.id} missing pattern")
      ++ missingRule.map(check => s"$path: scalafix rule ${check.id} missing rule name")
      ++ severityErrors.map(err => s"$path: ${err.display}")
      ++ ruleIdShape.map(check => s"$path: rule id '${check.id}' must be dotted (e.g. scala.no-final-case-class)")

  private val RuleIdPattern = "^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$".r

  private def runAllFixtures(checks: List[MechanicalCheck], fixturesRoot: os.Path) =
    val checksById = checks.map(check => check.id -> check).toMap
    val fixtureDirs = os.list(fixturesRoot).filter(os.isDir)
    fixtureDirs.toList.flatMap: dir =>
      val ruleId = dir.last
      checksById.get(ruleId) match
        case None        => List(s"$dir: no rule with id '$ruleId'" -> false)
        case Some(check) => runFixture(check, dir)

  private def runFixture(check: MechanicalCheck, dir: os.Path) =
    val bad = dir / "bad.scala"
    val good = dir / "good.scala"
    val badOutcome = Option.when(os.exists(bad))(testBad(check, bad)).flatten
    val goodOutcome = Option.when(os.exists(good))(testGood(check, good)).flatten
    (badOutcome.toList ++ goodOutcome.toList).map: (label, succeeded) =>
      s"${check.id}/$label" -> succeeded

  private def testBad(check: MechanicalCheck, file: os.Path) =
    check.pattern.map: pattern =>
      "bad" -> patternMatchesContent(pattern, os.read(file))

  private def testGood(check: MechanicalCheck, file: os.Path) =
    check.pattern.map: pattern =>
      "good" -> !patternMatchesContent(pattern, os.read(file))

  private def patternMatchesContent(pattern: String, content: String) =
    s"(?m)$pattern".r.findFirstIn(content).isDefined
