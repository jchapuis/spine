package compliancegate

import org.virtuslab.yaml.*

import GateError.*

case class SkillFrontmatter(
    name: String,
    description: String,
    triggers: Option[List[TriggerEntry]] = None,
    mechanicalChecks: Option[List[MechanicalCheck]] = None
)

case class TriggerEntry(
    matchMode: TriggerMatch = TriggerMatch.Any,
    filePatterns: List[String] = List.empty,
    imports: List[String] = List.empty,
    dependencies: List[String] = List.empty,
    pathsExclude: List[String] = List.empty
)

enum TriggerMatch:
  case Any, All

case class MechanicalCheck(
    id: String,
    tool: String,
    severity: Option[String] = None,
    pattern: Option[String] = None,
    rule: Option[String] = None,
    config: Option[String] = None,
    filesMustImport: List[String] = List.empty,
    paths: List[String] = List.empty,
    pathsExclude: List[String] = List.empty,
    aggregate: Option[String] = None,
    threshold: Option[Int] = None,
    multiline: Boolean = false,
    message: String,
    references: List[String] = List.empty
):
  def resolvedSeverity: Severity = severity.flatMap(Severity.parse(_).toOption).getOrElse(Severity.Error)

object Frontmatter:
  private val Delimiter = "---"

  case class LoadResult(skills: List[(os.Path, SkillFrontmatter)], errors: List[GateError])

  def extract(path: os.Path, content: String): Either[GateError, String] =
    content.linesIterator.toList match
      case head :: rest if head.trim == Delimiter =>
        val (body, _) = rest.span(line => line.trim != Delimiter)
        Right(body.mkString("\n"))
      case _ => Left(FrontmatterMissingDelimiter(path))

  def parse(skillPath: os.Path): Either[GateError, SkillFrontmatter] =
    for
      yamlText    <- extract(skillPath, os.read(skillPath))
      node        <- yamlText.asNode.left.map(error => FrontmatterInvalid(skillPath, error.msg))
      frontmatter <- toFrontmatter(skillPath, node)
    yield frontmatter

  def loadAll(skillsRoot: os.Path): LoadResult =
    if !os.isDir(skillsRoot) then LoadResult(List.empty, List.empty)
    else
      val skillFiles = os.list(skillsRoot).filter(os.isDir).map(_ / "SKILL.md").filter(os.exists)
      val outcomes = skillFiles.toList.map(path => path -> parse(path))
      val skills = outcomes.collect:
        case (path, Right(skill)) => path -> skill
      val errors = outcomes.collect:
        case (_, Left(error)) => error
      LoadResult(skills, errors)

  def loadSkills(skillsRoot: os.Path): List[(os.Path, SkillFrontmatter)] =
    val result = loadAll(skillsRoot)
    result.errors.foreach(error => System.err.println(s"compliance-gate: skipped ${error.display}"))
    result.skills

  private def toFrontmatter(path: os.Path, node: Node) =
    fields(path, node).flatMap: map =>
      for
        name        <- requiredString(path, map, "name")
        description <- requiredString(path, map, "description")
        triggers    <- entries(path, map, "triggers", toTrigger(path, _))
        checks      <- entries(path, map, "mechanical_checks", toCheck(path, _))
      yield SkillFrontmatter(name, description, triggers, checks)

  private def toTrigger(path: os.Path, node: Node) =
    fields(path, node).flatMap: map =>
      val matchMode = optString(map, "match").map(_.toLowerCase) match
        case Some("all") => TriggerMatch.All
        case _           => TriggerMatch.Any
      for
        filePatterns <- stringList(path, map, "file_patterns")
        imports      <- stringList(path, map, "imports")
        dependencies <- stringList(path, map, "dependencies")
        pathsExclude <- stringList(path, map, "paths_exclude")
      yield TriggerEntry(matchMode, filePatterns, imports, dependencies, pathsExclude)

  private def toCheck(path: os.Path, node: Node) =
    fields(path, node).flatMap: map =>
      for
        id              <- requiredString(path, map, "id")
        tool            <- requiredString(path, map, "tool")
        message         <- requiredString(path, map, "message")
        filesMustImport <- stringList(path, map, "files_must_import")
        paths           <- stringList(path, map, "paths")
        pathsExclude    <- stringList(path, map, "paths_exclude")
        references      <- stringList(path, map, "references")
      yield MechanicalCheck(
        id = id,
        tool = tool,
        severity = optString(map, "severity"),
        pattern = optString(map, "pattern"),
        rule = optString(map, "rule"),
        config = optString(map, "config"),
        filesMustImport = filesMustImport,
        paths = paths,
        pathsExclude = pathsExclude,
        aggregate = optString(map, "aggregate"),
        threshold = optString(map, "threshold").flatMap(_.toIntOption),
        multiline = optString(map, "multiline").exists(value => value == "true" || value == "yes"),
        message = message,
        references = references
      )

  private def fields(path: os.Path, node: Node) =
    node match
      case Node.MappingNode(mappings, _) =>
        val byName = mappings.collect:
          case (Node.ScalarNode(key, _), value) => key -> value
        Right(byName)
      case _ => Left(FrontmatterInvalid(path, "frontmatter must be a mapping"))

  private def requiredString(path: os.Path, map: Map[String, Node], key: String) =
    map.get(key).toRight(FrontmatterInvalid(path, s"missing string field: $key")).flatMap(decode[String](path, key, _))

  private def optString(map: Map[String, Node], key: String) =
    map.get(key).flatMap(_.as[String].toOption)

  private def stringList(path: os.Path, map: Map[String, Node], key: String) =
    map.get(key) match
      case None       => Right(List.empty)
      case Some(node) => decode[List[String]](path, key, node)

  private def entries[A](
      path: os.Path,
      map: Map[String, Node],
      key: String,
      parse: Node => Either[GateError, A]
  ) =
    map.get(key) match
      case None                              => Right(None)
      case Some(Node.SequenceNode(nodes, _)) => traverse(nodes.toList, parse).map(Some(_))
      case Some(_)                           => Left(FrontmatterInvalid(path, s"$key must be a sequence"))

  private def traverse[A](nodes: List[Node], parse: Node => Either[GateError, A]) =
    nodes.foldRight[Either[GateError, List[A]]](Right(List.empty)): (node, acc) =>
      acc.flatMap(tail => parse(node).map(_ :: tail))

  private def decode[T](path: os.Path, key: String, node: Node)(using YamlDecoder[T]) =
    node.as[T].left.map(error => FrontmatterInvalid(path, s"$key: ${error.msg}"))
