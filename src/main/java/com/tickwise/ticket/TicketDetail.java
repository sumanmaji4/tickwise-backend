package com.tickwise.ticket;

import com.tickwise.sla.SlaSnapshot;

public record TicketDetail(TicketView ticket, SlaSnapshot sla) {}
