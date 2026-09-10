package net.citotech.cito.analytics;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrative growth scorecard backed by canonical Cito operational data. */
@RestController
@RequestMapping(path = "/api/v2/admin/growth")
@PreAuthorize("hasRole('ADMIN')")
public class GrowthIntelligenceController {
    private final GrowthIntelligenceService intelligenceService;
    private final GrowthMilestoneProjectionService projectionService;

    public GrowthIntelligenceController(
            GrowthIntelligenceService intelligenceService,
            GrowthMilestoneProjectionService projectionService) {
        this.intelligenceService = intelligenceService;
        this.projectionService = projectionService;
    }

    @GetMapping(path = "/definitions")
    public Map<String, Object> definitions() {
        return intelligenceService.definitions();
    }

    @GetMapping(path = "/scorecard")
    public Map<String, Object> scorecard(
            @RequestParam(name = "windowDays", defaultValue = "30") int windowDays) {
        return intelligenceService.scorecard(windowDays);
    }

    @GetMapping(path = "/merchants/{merchantId}")
    public Map<String, Object> merchant(
            @PathVariable long merchantId,
            @RequestParam(name = "windowDays", defaultValue = "30") int windowDays) {
        return intelligenceService.merchant(merchantId, windowDays);
    }

    @PostMapping(path = "/milestones/reconcile")
    public Map<String, Object> reconcileMilestones() {
        return Map.of("projected", projectionService.reconcile());
    }
}
