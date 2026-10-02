type OrderId = OrderId.T
object OrderId extends RefinedSubtype[UUID, Pure]

type InputId = InputId.T
object InputId extends RefinedSubtype[UUID, Pure]

type TenantId = TenantId.T
object TenantId extends RefinedSubtype[Long, Pure]
