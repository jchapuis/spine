package compliancegate

import GateError.*

object SkillsRoot:
  val EnvVariable = "SPINE_SKILLS_ROOT"

  def resolve(explicit: Option[os.Path]): Either[GateError, os.Path] =
    explicit
      .map(path => path -> "--skills-root")
      .orElse(sys.env.get(EnvVariable).map(value => os.Path(value, os.pwd) -> EnvVariable))
      .toRight(SkillsRootUnset)
      .flatMap(validate)

  def validate(candidate: os.Path, source: String): Either[GateError, os.Path] =
    Either.cond(os.isFile(candidate / "compliance-gate" / "SKILL.md"), candidate, NotASkillsRoot(candidate, source))
