package com.example.shop.mcp.access;

import com.example.shop.mcp.access.MemberService.Member;
import com.example.shop.mcp.core.Area;
import com.example.shop.mcp.core.ToolSpec;

/**
 * Three independent checks, all required. The same function filters tools/list and is re-run on
 * every tools/call.
 * <ol>
 *   <li><b>Scope</b>: a write tool needs {@code shop.write} on the grant (opt-in at consent).</li>
 *   <li><b>Area</b>: the user opened this area (catalog, orders...) to this AI connection.</li>
 *   <li><b>Role</b>: the user's role has the tool's permission key, as for the app screen.</li>
 * </ol>
 */
public final class AccessPolicy {

    private AccessPolicy() {
    }

    public sealed interface Verdict {
        record Allowed() implements Verdict {
        }

        record Denied(String reason, String message) implements Verdict {
        }
    }

    public static Verdict evaluate(ToolSpec<?> spec, Grant grant, Member member) {
        if (spec.write() && !grant.canWrite()) {
            return new Verdict.Denied("scope", "This connection is read-only. Reconnect and allow changes to let "
                    + "the assistant " + spec.title().toLowerCase() + ".");
        }
        if (spec.area() != Area.CORE && !grant.areas().contains(spec.area())) {
            return new Verdict.Denied("area", "The " + spec.area().key()
                    + " area was not opened to this assistant. The user can reconnect and allow it.");
        }
        if (!member.has(spec.permission())) {
            return new Verdict.Denied("role", "Your role in this shop cannot use this function (needs "
                    + spec.permission() + ").");
        }
        return new Verdict.Allowed();
    }

    public static boolean allowed(ToolSpec<?> spec, Grant grant, Member member) {
        return evaluate(spec, grant, member) instanceof Verdict.Allowed;
    }
}
