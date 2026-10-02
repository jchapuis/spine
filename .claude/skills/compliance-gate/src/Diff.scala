package compliancegate

enum FileStatus:
  case Added, Modified, Deleted

case class ChangedFile(path: os.RelPath, status: FileStatus):
  def isScala: Boolean = path.toString.endsWith(".scala") || path.toString.endsWith(".sc")
  def isPython: Boolean = path.toString.endsWith(".py")
  def isCode: Boolean = isScala || isPython

enum DiffSource:
  case Staged
  case Workdir
  case Branch(base: String)

object Diff:
  def changedFiles(projectRoot: os.Path, source: DiffSource): Either[GateError, List[ChangedFile]] =
    val diffArgs = source match
      case DiffSource.Staged       => List("diff", "--cached", "--name-status", "--diff-filter=ACMRD")
      case DiffSource.Workdir      => List("diff", "HEAD", "--name-status", "--diff-filter=ACMRD")
      case DiffSource.Branch(base) => List("diff", s"$base...HEAD", "--name-status", "--diff-filter=ACMRD")
    val output = git(projectRoot, List("-c", "core.quotePath=false") ++ diffArgs).trim
    val untracked = source match
      case DiffSource.Workdir => untrackedFiles(projectRoot)
      case _                  => List.empty
    val (unparseable, files) = output.linesIterator.filter(_.nonEmpty).toList.map(parseNameStatusLine).partitionMap(identity)
    unparseable.headOption.toLeft(files ++ untracked)

  private def untrackedFiles(projectRoot: os.Path) =
    git(projectRoot, List("-c", "core.quotePath=false", "ls-files", "--others", "--exclude-standard")).linesIterator
      .filter(_.nonEmpty)
      .map(path => ChangedFile(os.RelPath(path), FileStatus.Added))
      .toList

  def parseNameStatusLine(line: String): Either[GateError, ChangedFile] =
    line.split('\t').toList match
      case statusTag :: paths if paths.nonEmpty =>
        val destination = paths.last
        parseStatus(statusTag)
          .map(status => ChangedFile(os.RelPath(destination), status))
          .toRight(GateError.UnreadableDiffLine(line))
      case _ => Left(GateError.UnreadableDiffLine(line))

  def resolveBase(projectRoot: os.Path, explicit: Option[String]): Option[String] =
    val candidates = explicit.toList ++ List("origin/master", "origin/main", "master", "main")
    candidates.find: ref =>
      gitOpt(projectRoot, List("rev-parse", "--verify", "--quiet", ref)).map(_.trim.nonEmpty).getOrElse(false)

  def mergeBase(projectRoot: os.Path, base: String): Option[String] =
    gitOpt(projectRoot, List("merge-base", "HEAD", base)).map(_.trim).filter(_.nonEmpty)

  def readFileHead(projectRoot: os.Path, path: os.RelPath, maxLines: Int = 100): String =
    val absolute = projectRoot / path
    if !os.exists(absolute) then ""
    else os.read.lines(absolute).take(maxLines).mkString("\n")

  def readBuildDescriptor(projectRoot: os.Path): String =
    val candidates = List(
      projectRoot / "build.sbt",
      projectRoot / "project.scala",
      projectRoot / "app" / "build.sbt"
    )
    candidates.filter(os.exists).map(os.read(_)).mkString("\n")

  private def parseStatus(tag: String) = tag.headOption match
    case Some('A')                          => Some(FileStatus.Added)
    case Some('M') | Some('R') | Some('C')  => Some(FileStatus.Modified)
    case Some('D')                          => Some(FileStatus.Deleted)
    case _                                  => None

  private def git(projectRoot: os.Path, args: List[String]) =
    os.proc("git" :: args).call(cwd = projectRoot, check = true).out.text()

  private def gitOpt(projectRoot: os.Path, args: List[String]) =
    val result = os.proc("git" :: args).call(cwd = projectRoot, check = false, stderr = os.Pipe)
    if result.exitCode == 0 then Some(result.out.text()) else None
