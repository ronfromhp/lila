package lila.clas

import cats.data.NonEmptyList
import org.joda.time.DateTime
import ornicar.scalalib.ThreadLocalRandom

import lila.user.User

case class Clas(
    _id: Clas.Id,
    name: String,
    desc: String,
    wall: Markdown = Markdown(""),
    teachers: NonEmptyList[UserId], // first is owner
    created: Clas.Recorded,
    viewedAt: DateTime,
    archived: Option[Clas.Recorded]
):

  inline def id = _id

  def withStudents(students: List[Student]) = Clas.WithStudents(this, students)

  def isArchived = archived.isDefined
  def isActive   = !isArchived

object Clas:

  val maxStudents = 100

  def make(teacher: User, name: String, desc: String) =
    Clas(
      _id = Id(ThreadLocalRandom nextString 8),
      name = name,
      desc = desc,
      teachers = NonEmptyList.one(teacher.id),
      created = Recorded(teacher.id, DateTime.now),
      viewedAt = DateTime.now,
      archived = none
    )

  opaque type Id = String
  object Id extends OpaqueString[Id]

  case class Recorded(by: UserId, at: DateTime)

  case class WithStudents(clas: Clas, students: List[Student])
