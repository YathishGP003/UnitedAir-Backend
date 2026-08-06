package com.unitedair.ai.providers;

import com.unitedair.ai.tools.ToolDtos;

public interface CheckInProvider {

    ToolDtos.CheckInEligibility eligibility(ToolDtos.BookingView booking);

    ProviderCapability capability();
}
