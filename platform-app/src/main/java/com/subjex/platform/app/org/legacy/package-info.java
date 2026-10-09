/**
 * Legacy org compatibility (O8-3/O8-4) — thin adapters over the Organization ontology.
 * <p>
 * <strong>Dependency rule:</strong> this package may depend on
 * {@code com.subjex.platform.app.organization} and {@code ...security.OrganizationScope}.
 * New API, Policy, zero-code, and declaration code must <em>not</em> depend on this package
 * (Legacy → New only; never New → Legacy).
 * <p>
 * Contains deprecated {@code /api/v1/org/**}, {@link com.subjex.platform.app.org.legacy.JdbcOrgDirectory},
 * {@link com.subjex.platform.app.org.legacy.OrgUnit} / {@link com.subjex.platform.app.org.legacy.OrgMembership}
 * projections, and deprecated {@link com.subjex.platform.app.org.legacy.OrgScope}.
 * <p>
 * O8-4: directory reads are ontology-first (no map joins). Map write-through remains only for
 * legacy adapter upserts via {@code OrganizationOntologyBackfill}; formal Organization API does not
 * call backfill.
 */
package com.subjex.platform.app.org.legacy;
