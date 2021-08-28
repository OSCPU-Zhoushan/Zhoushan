package zhoushan

object MaskExpand {
  def apply(x: UInt) = Cat(m.asBools.map(Fill(8, _)).reverse)
}

object MaskData {
  def apply(old_data: UInt, new_data: UInt, mask: UInt) = {
    (new_data & mask) | (old_data & ~mask)
  }
}
