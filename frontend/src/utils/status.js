// 闸门/事实/快照状态展示映射
export function statusText(s) {
  return {
    DRAFT: '草稿', VERIFIED: '已验证', RETIRED: '已废止', REVIEW: '待复核', FROZEN: '冻结'
  }[s] || s
}
export function statusType(s) {
  return {
    DRAFT: 'info', VERIFIED: 'success', RETIRED: 'danger', REVIEW: 'warning', FROZEN: 'danger'
  }[s] || 'info'
}
export function verdictText(v) {
  return { PASS: '通过', FAIL: '不通过', INSUFFICIENT_EVIDENCE: '证据不足' }[v] || v
}
export function verdictType(v) {
  return { PASS: 'success', FAIL: 'danger', INSUFFICIENT_EVIDENCE: 'warning' }[v] || 'info'
}
export function entityTypeText(t) {
  return {
    PART_MODEL: '部件型号', PHYSICAL_PART: '实物序列号', MOUNT_POSITION: '装配位置',
    EQUIPMENT: '设备序列号', SUPPLIER: '供应商编码'
  }[t] || t
}
// 维修通知资格四档 + 待核
export function qualText(q) {
  return {
    CERTAIN: '必然安装', POSSIBLE: '可能安装', INSUFFICIENT: '证据不足',
    EXCLUDED: '可排除', PENDING_REVIEW: '矛盾待核'
  }[q] || q
}
export function qualType(q) {
  return {
    CERTAIN: 'success', POSSIBLE: 'warning', INSUFFICIENT: 'info',
    EXCLUDED: '', PENDING_REVIEW: 'danger'
  }[q] || 'info'
}
export function evidenceNodeText(t) {
  return {
    INSTALL_SIGN: '安装签字', REMOVE_SIGN: '拆除签字', INVENTORY_COUNT: '盘点凭证',
    FACTORY_RECORD: '出厂记录', ASSEMBLY_TRANSFER: '总成转装', REPAIR_ORDER: '维修单', RULE_CLAIM: '规则主张'
  }[t] || t
}
