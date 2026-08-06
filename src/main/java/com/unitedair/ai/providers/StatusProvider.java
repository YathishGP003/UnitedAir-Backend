package com.unitedair.ai.providers;

import java.time.LocalDate;

import com.unitedair.ai.tools.ToolDtos;

public interface StatusProvider {

    ToolDtos.FlightStatusView status(String flightNo, LocalDate date);

    ProviderCapability capability();
}
