package com.vn.vitalcare.entity;

import com.vn.vitalcare.share.data.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One note a member of staff wrote while following a patient (SRS 3.9.3).
 * Who wrote it is {@code created_by_id}; only they may change it afterwards.
 */
@Entity
@Table(name = "monitoring_note")
public class MonitoringNote extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    public MonitoringNote() {
    }

    public MonitoringNote(Customer customer, String content) {
        this.customer = customer;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
