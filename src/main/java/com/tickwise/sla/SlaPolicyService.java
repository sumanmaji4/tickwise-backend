package com.tickwise.sla;

import com.tickwise.shared.domain.Priority;
import com.tickwise.shared.error.NotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SlaPolicyService {

    /** Business-minute targets for new tenants: {first response, resolution}. */
    static final Map<Priority, int[]> DEFAULTS = Map.of(
            Priority.URGENT, new int[] {60, 4 * 60},
            Priority.HIGH, new int[] {4 * 60, 16 * 60},
            Priority.MEDIUM, new int[] {8 * 60, 40 * 60},
            Priority.LOW, new int[] {16 * 60, 80 * 60});

    private final SlaPolicyRepository policies;

    SlaPolicyService(SlaPolicyRepository policies) {
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public List<SlaPolicyView> list(UUID tenantId) {
        return policies.findAllByTenantId(tenantId).stream()
                .map(SlaPolicy::toView)
                .sorted(Comparator.comparing(SlaPolicyView::priority).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public SlaPolicyView get(UUID tenantId, Priority priority) {
        return policies.findByTenantIdAndPriority(tenantId, priority).map(SlaPolicy::toView)
                .orElseThrow(() -> new NotFoundException("SLA policy", priority));
    }

    /**
     * Changes apply to tickets created or re-prioritised afterwards; running clocks keep the
     * target they started with, so a policy edit never silently moves existing deadlines.
     */
    @Transactional
    public SlaPolicyView update(UUID tenantId, Priority priority, int firstResponseMinutes, int resolutionMinutes) {
        SlaPolicy policy = policies.findByTenantIdAndPriority(tenantId, priority)
                .orElseGet(() -> policies.save(new SlaPolicy(tenantId, priority, firstResponseMinutes, resolutionMinutes)));
        policy.update(firstResponseMinutes, resolutionMinutes);
        return policy.toView();
    }

    @Transactional
    void provisionDefaults(UUID tenantId) {
        DEFAULTS.forEach((priority, minutes) -> {
            if (policies.findByTenantIdAndPriority(tenantId, priority).isEmpty()) {
                policies.save(new SlaPolicy(tenantId, priority, minutes[0], minutes[1]));
            }
        });
    }
}
