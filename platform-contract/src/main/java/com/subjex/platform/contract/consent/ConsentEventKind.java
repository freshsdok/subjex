package com.subjex.platform.contract.consent;

/**
 * ConsentEventKind — append-only ledger fact: a grant or a per-purpose withdrawal.
 * 追加式台账事实：一次授予，或一次按目的的撤销。
 */
public enum ConsentEventKind {
    GRANT,
    WITHDRAW
}
