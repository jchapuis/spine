package compliancegate

enum GateError:
  case FrontmatterMissingDelimiter(path: os.Path)
  case FrontmatterInvalid(path: os.Path, reason: String)
  case SkillSchema(path: os.Path, reason: String)
  case InvalidSeverity(value: String)
  case SkillsRootUnset
  case NotASkillsRoot(candidate: os.Path, source: String)
  case NoRuleBearingSkills(skillsRoot: os.Path)
  case UnknownSkillNames(names: List[String], known: List[String])
  case UnreadableDiffLine(line: String)

object GateError:
  extension (error: GateError)
    def display: String = error match
      case FrontmatterMissingDelimiter(path)    => s"$path: SKILL.md does not start with `---` frontmatter delimiter"
      case FrontmatterInvalid(path, reason)     => s"$path: $reason"
      case SkillSchema(path, reason)            => s"$path: $reason"
      case InvalidSeverity(value)               => s"unknown severity: $value"
      case UnreadableDiffLine(line)             =>
        s"unreadable `git diff --name-status` line — refusing to scan a partial diff: $line"
      case SkillsRootUnset                      =>
        s"no skills root: pass --skills-root or set ${SkillsRoot.EnvVariable} (bin/compliance-gate sets it to the skills directory it lives in)"
      case NotASkillsRoot(candidate, source)    =>
        s"$candidate (from $source) is not a compliance-gate skills root — it has no compliance-gate/SKILL.md"
      case NoRuleBearingSkills(skillsRoot)      =>
        s"$skillsRoot holds no skill declaring mechanical_checks — refusing to report a clean result from an empty rule set"
      case UnknownSkillNames(names, known)      =>
        s"--skills names no loaded skill: ${names.mkString(", ")} (known: ${known.mkString(", ")})"
