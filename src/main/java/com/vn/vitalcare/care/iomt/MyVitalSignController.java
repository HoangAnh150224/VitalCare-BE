package com.vn.vitalcare.care.iomt;

import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.VitalSigns;
import com.vn.vitalcare.share.security.Permissions;
import com.vn.vitalcare.share.web.ListResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/my_vital_signs} — a patient's own vital signs and those of the
 * dependants their account manages (the portal's and the app's "Sức khỏe").
 * {@code profileId} picks the profile; left out, the account's own.
 */
@RestController
@RequestMapping("/api/my_vital_signs")
@PreAuthorize("hasAuthority('" + Permissions.MY_VITAL_SIGNS_READ + "')")
public class MyVitalSignController {

    private final VitalViews views;

    public MyVitalSignController(VitalViews views) {
        this.views = views;
    }

    @GetMapping("/latest")
    public VitalViewDtos.Latest latest(@RequestParam(required = false) Long profileId) {
        return views.latest(views.ownProfile(profileId));
    }

    @GetMapping
    public ResponseEntity<List<VitalViewDtos.Reading>> history(
            @RequestParam(required = false) Long profileId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(name = "_start", defaultValue = "0") int start,
            @RequestParam(name = "_end", defaultValue = "50") int end) {
        Customer profile = views.ownProfile(profileId);
        // A patient sees measurements, never the readings set aside as sensor faults.
        Page<VitalSigns> page = views.history(profile, from, to, false, start, end);
        return ListResponse.of(page.getContent().stream().map(VitalViewDtos.Reading::of).toList(), page.getTotalElements());
    }

    @GetMapping("/trend")
    public VitalViewDtos.Trend trend(@RequestParam(required = false) Long profileId,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        return views.trend(views.ownProfile(profileId), from, to);
    }

    @GetMapping("/{id}")
    public VitalViewDtos.Detail get(@PathVariable Long id) {
        VitalSigns v = views.one(id, views::ownProfile);
        if (!v.isValid()) {
            throw new com.vn.vitalcare.share.exception.ResourceNotFoundException("Vital sign", id);
        }
        return VitalViewDtos.Detail.of(v);
    }
}
