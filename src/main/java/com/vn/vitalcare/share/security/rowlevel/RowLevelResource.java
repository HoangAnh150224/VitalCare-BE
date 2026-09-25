package com.vn.vitalcare.share.security.rowlevel;

import java.lang.annotation.*;

/**
 * Marks an entity as being under row-level management, and names the resource
 * it is.
 *
 * <p>The value is the resource name <em>verbatim</em> — the same string the
 * frontend declares in {@code routes.tsx}, the same first half as the
 * permission codes in {@code Permissions}, and the same segment the controller
 * is mapped at. {@code blog_posts}, underscore included. There is no lookup
 * table between the layers to fall out of step, so a typo here is a startup
 * failure rather than a policy that silently applies to nothing.
 *
 * <p>Marking an entity is only half of it: the resource also needs exactly one
 * {@link RowLevelPolicySet} bean, declaring which fields a policy may mention
 * and what a role with no policy may reach. {@code RowLevelMetadataValidator}
 * refuses to start the application if either half is missing.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RowLevelResource {

    /** The resource name, exactly as the frontend and the permission codes spell it. */
    String value();
}
