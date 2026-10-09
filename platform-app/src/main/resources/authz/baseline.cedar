// AuthZ-1d baseline — permission membership (tenant/org host-side via AccessChecker).
// Default deny when when-clause fails. No self-authored DSL.
// Anonymous/unauthenticated: host maps null/blank subjectId → Subject::__unauthenticated__
// (empty permissions → deny); real subject id "anonymous" is a normal principal (parity with SQL).
@id("allow_if_permission_held")
permit (
  principal,
  action == Action::"invoke",
  resource
) when {
  context has requiredPermission &&
  principal has permissions &&
  principal.permissions.contains(context.requiredPermission)
};
