package com.swaroop.spendinsights.web;

import com.swaroop.spendinsights.service.TransactionService;
import com.swaroop.spendinsights.web.dto.Dtos.TransactionResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** ADMIN-only (enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final TransactionService txns;
    public AdminController(TransactionService txns) { this.txns = txns; }

    @GetMapping("/flagged-transactions")
    public List<TransactionResponse> flagged() { return txns.flagged(); }
}
