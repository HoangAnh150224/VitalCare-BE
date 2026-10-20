package com.vn.vitalcare.care.medicalrecord.service.impl;

import com.vn.vitalcare.care.customer.service.CustomerService;
import com.vn.vitalcare.care.medicalrecord.dto.MonitoringNoteRequest;
import com.vn.vitalcare.care.medicalrecord.dto.MonitoringNoteResponse;
import com.vn.vitalcare.care.medicalrecord.repository.MonitoringNoteRepository;
import com.vn.vitalcare.care.medicalrecord.service.MonitoringNoteService;
import com.vn.vitalcare.entity.Customer;
import com.vn.vitalcare.entity.MonitoringNote;
import com.vn.vitalcare.identity.user.entity.User;
import com.vn.vitalcare.identity.user.service.UserService;
import org.springframework.security.access.AccessDeniedException;
import com.vn.vitalcare.share.exception.ResourceNotFoundException;
import com.vn.vitalcare.share.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The {@link MonitoringNoteService} the application runs on. */
@Service
@Transactional(readOnly = true)
public class MonitoringNoteServiceImpl implements MonitoringNoteService {

    private final MonitoringNoteRepository repository;
    // The clinic boundary, as for the medical record.
    private final CustomerService customerService;
    private final UserService users;

    public MonitoringNoteServiceImpl(MonitoringNoteRepository repository, CustomerService customerService,
                                     UserService users) {
        this.repository = repository;
        this.customerService = customerService;
        this.users = users;
    }

    @Override
    public List<MonitoringNoteResponse> list(Long customerId) {
        customerService.get(customerId);
        List<MonitoringNote> notes = repository.findByCustomerIdAndDeletedAtIsNullOrderByCreatedAtDesc(customerId);
        Map<Long, String> names = authors(notes.stream().map(MonitoringNote::getCreatedById).toList());
        Long caller = CurrentUser.id().orElse(null);
        return notes.stream().map(note -> MonitoringNoteResponse.from(note, names, caller)).toList();
    }

    @Override
    @Transactional
    public MonitoringNoteResponse add(Long customerId, MonitoringNoteRequest request) {
        Customer patient = customerService.get(customerId);
        MonitoringNote saved = repository.saveAndFlush(new MonitoringNote(patient, request.content().trim()));
        return respond(saved);
    }

    @Override
    @Transactional
    public MonitoringNoteResponse update(Long customerId, Long noteId, MonitoringNoteRequest request) {
        customerService.get(customerId);
        MonitoringNote note = repository.findByIdAndCustomerIdAndDeletedAtIsNull(noteId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Monitoring note", noteId));
        if (!Objects.equals(note.getCreatedById(), CurrentUser.id().orElse(null))) {
            throw new AccessDeniedException("Only the author of a note may change it");
        }
        note.setContent(request.content().trim());
        return respond(repository.saveAndFlush(note));
    }

    private MonitoringNoteResponse respond(MonitoringNote note) {
        return MonitoringNoteResponse.from(note, authors(List.of(note.getCreatedById())), CurrentUser.id().orElse(null));
    }

    private Map<Long, String> authors(List<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        return wanted.isEmpty() ? Map.of()
                : users.getMany(wanted).stream().collect(Collectors.toMap(User::getId, User::getFullName));
    }
}
