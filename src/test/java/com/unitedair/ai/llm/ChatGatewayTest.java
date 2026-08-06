package com.unitedair.ai.llm;

import com.unitedair.ai.grounding.CitationAttacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

class ChatGatewayTest {

    @SuppressWarnings("unchecked")
    @Test
    void offlineCompositionReportsWhyItDegraded() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));

        ChatDtos.ChatResult result = gateway.complete(new ChatDtos.ChatRequest(
                "system", List.of(), "What is the baggage allowance?",
                List.of(new ChatDtos.Grounding(
                        "E1", "Baggage", "Economy checked baggage is limited to 15 kg."))));

        assertThat(result.live()).isFalse();
        assertThat(result.degradedReason()).isEqualTo("OFFLINE_CONFIGURED");
    }

    @SuppressWarnings("unchecked")
    @Test
    void offlineCompositionPrefersTheBestRankedEvidenceForDensePolicyTables() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));

        ChatDtos.ChatResult result = gateway.complete(new ChatDtos.ChatRequest(
                "system", List.of(),
                """
                EVIDENCE from the UnitedAir Knowledge Base:
                Fare Category Cancellation Timing Refund Taxes Change Difference.
                Route Type Check-In Opens Web Mobile Kiosk Counter Baggage Drop Closes.
                Passengers presenting after closure are denied boarding regardless of status.
                QUESTION: What is the cancellation fee for a Value fare more than 7 days before departure?
                """,
                List.of(
                        new ChatDtos.Grounding("E1", "Cancellation policy",
                                """
                                Fare Category | Cancellation Timing | Cancellation Fee | Refund of Base Fare | Refund of Taxes
                                Saver / Super Saver | Any time | 100% of base fare forfeited | Nil | Statutory taxes refunded
                                Value | More than 7 days before departure | INR 2,000 per Passenger | Balance after fee | Yes
                                Value | 3-7 days before departure | INR 3,000 per Passenger | Balance after fee | Yes
                                Value | Within 3 days of departure | INR 4,000 per Passenger | Balance after fee | Yes
                                Flex | More than 3 hours before departure | INR 1,000 per Passenger | Balance after fee | Yes
                                Full Flex | Any time up to 2 hours before departure | Nil | Full base fare | Yes
                                Business Saver | More than 7 days before departure | INR 4,000 per Passenger | Balance after fee | Yes
                                Business Saver | Within 7 days of departure | INR 5,000 per Passenger | Balance after fee | Yes
                                Business Flex | Any time up to 2 hours before departure | Nil | Full base fare | Yes
                                """),
                        new ChatDtos.Grounding("E2", "Rescheduling",
                                """
                                Fare Category | Change Timing | Change Fee | Fare Difference
                                Saver / Super Saver | Not permitted | - | -
                                Value | More than 7 days before departure | INR 1,500 per Passenger | If new fare is higher
                                Value | Within 7 days of departure | INR 2,500 per Passenger | If new fare is higher
                                Flex | Any time up to 4 hours before departure | INR 750 per Passenger | If new fare is higher
                                Full Flex | Any time up to 2 hours before departure | Nil | If new fare is higher
                                """),
                        new ChatDtos.Grounding("E3", "Fare overview",
                                """
                                Fare Category | Date Change | Cancellation | Refund | Advance Purchase
                                Saver / Super Saver | Not allowed; name correction only | Not permitted | Non-refundable | 7-90 days prior
                                Value / Smart | Allowed with change fee INR 1,500-3,000 + fare difference | Cancellation fee applies | Partial refund of statutory taxes | 1-30 days prior
                                Flex / Full Flex | Free change up to 2 hours before departure | Free cancellation up to 2 hours before departure | Full refund | Any time after booking
                                """),
                        new ChatDtos.Grounding("E4", "Check-in",
                                """
                                Route Type | Check-In Opens | Web Check-In Closes | Counter Check-In Closes
                                Domestic | 48 hours before departure | 1 hour before departure | 45 minutes before departure
                                International Short Haul | 48 hours before departure | 2 hours before departure | 75 minutes before departure
                                International Long Haul | 48 hours before departure | 3 hours before departure | 90 minutes before departure
                                Passengers presenting after counter closure will be denied boarding regardless of check-in status.
                                """))));

        assertThat(result.text())
                .contains("INR 2,000", "[E1]")
                .doesNotContain(
                        "INR 3,000",
                        "INR 4,000",
                        "Business Saver",
                        "denied boarding");
    }

    @SuppressWarnings("unchecked")
    @Test
    void generalCancellationQuestionSummarisesTheMainFareOptions() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));

        ChatDtos.ChatResult result = gateway.complete(new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: What is the cancellation policy for domestic flights?",
                List.of(new ChatDtos.Grounding("E1", "Cancellation matrix", """
                        2 Passenger-Initiated Cancellation Policy
                        Fare Category | Cancellation Timing | Cancellation Fee | Refund of Base Fare | Refund of Taxes
                        Saver / Super Saver | Any time | 100% of base fare forfeited | Nil | Statutory taxes refunded
                        Value | More than 7 days before departure | INR 2,000 per Passenger | Balance after fee | Yes
                        Value | 3-7 days before departure | INR 3,000 per Passenger | Balance after fee | Yes
                        Flex | More than 3 hours before departure | INR 1,000 per Passenger | Balance after fee | Yes
                        Full Flex | Any time up to 2 hours before departure | Nil | Full base fare | Yes
                        """))));

        assertThat(result.text())
                .contains(
                        "Saver / Super Saver",
                        "Value - More than 7 days",
                        "Flex - More than 3 hours",
                        "Full Flex")
                .doesNotContain(
                        "Passenger-Initiated Cancellation Policy",
                        "Fare Category | Cancellation Timing");
    }

    @SuppressWarnings("unchecked")
    @Test
    void offlinePassengerCompositionSkipsInternalStaffTerminology() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));

        ChatDtos.ChatResult result = gateway.complete(new ChatDtos.ChatRequest(
                "YOU ARE SPEAKING TO A PASSENGER.",
                List.of(),
                "QUESTION: What is the cabin and checked baggage allowance "
                        + "on a domestic Economy ticket?",
                List.of(
                        new ChatDtos.Grounding("E1", "Checked baggage",
                                """
                                Travel Class | Fare Type | Free Allowance
                                Economy | Saver | No free checked baggage
                                Economy | Value | 15 kg
                                Economy | Smart | 20 kg
                                Economy | Flex | 25 kg
                                Passengers should verify the applicable allowance at booking.
                                """),
                        new ChatDtos.Grounding("E2", "Internal booking classes",
                                """
                                Booking Class | Cabin | Revenue Band | Typical Fare Characteristics
                                M | Economy | Standard | Value; standard change/cancel fees | Moderate
                                H | Economy | Standard | Value; higher advance purchase discount | Moderate
                                K | Economy | Low | Saver; high restrictions | Revenue managed
                                Q | Economy | Low | Super Saver; no change, no refund | Revenue managed
                                """))));

        assertThat(result.text())
                .contains("15 kg", "[E1]")
                .doesNotContainIgnoringCase("revenue band", "revenue managed");
    }

    @SuppressWarnings("unchecked")
    @Test
    void directConversationUsesTheSuppliedOfflineFallbackWithoutGrounding() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));

        ChatDtos.ChatResult result = gateway.completeDirect(
                "Return JSON only.", List.of(), "classify this", "{}");

        assertThat(result.text()).isEqualTo("{}");
        assertThat(result.live()).isFalse();
        assertThat(result.degradedReason()).isEqualTo("OFFLINE_CONFIGURED");
    }

    @SuppressWarnings("unchecked")
    @Test
    void deterministicGroundedFallbackSkipsDocumentMetadataRows() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: How should staff handle WCHR special services?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-006",
                        """
                        Document Code | KB-AIR-006
                        Category | Special Services & Loyalty
                        Audience | Passenger, Airline Staff
                        WCHR passengers require wheelchair assistance from check-in to the aircraft door.
                        Staff must confirm the assistance request in the booking before departure.
                        """)));

        ChatDtos.ChatResult result = gateway.composeGroundedFallback(request);

        assertThat(result.text())
                .contains("wheelchair assistance", "[E1]")
                .doesNotContain("Document Code", "Category |", "Audience |");
        assertThat(result.degradedReason())
                .isEqualTo("MODEL_CITATION_VALIDATION_FAILED");
    }

    @SuppressWarnings("unchecked")
    @Test
    void genericPolicyFallbackNeverReturnsDocumentHeaderMetadata() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: Explain the related baggage policy in more detail.",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-003",
                        """
                        UnitedAir AI | KB 03 Baggage Policy And Handling
                        ================================================
                        KB_03_Baggage_Policy_And_Handling | FR-006, FR-026 | US-06, US-09
                        Review Frequency | Annual or when baggage policy changes
                        KB Ingestion Tags | doc_type=baggage_policy, audience=passenger, version=v1.0
                        Economy passengers may carry one cabin bag up to 7 kg.
                        Economy Value fares include 15 kg of checked baggage.
                        """)));

        ChatDtos.ChatResult result = gateway.composeGroundedFallback(request);

        assertThat(result.text())
                .contains("checked baggage", "[E1]")
                .doesNotContain(
                        "UnitedAir AI | KB",
                        "KB_03_Baggage",
                        "Review Frequency",
                        "KB Ingestion Tags");
    }

    @SuppressWarnings("unchecked")
    @Test
    void baggageAnswerCombinesCabinAndCheckedAllowancesFromSeparateSections() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: What cabin and checked baggage allowance applies to an Economy Value ticket?",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-003",
                                """
                                3 Checked Baggage Policy
                                Economy | Saver | No free checked baggage
                                Economy | Value | 15 kg
                                Economy | Smart | 20 kg
                                Economy | Flex | 25 kg
                                """),
                        new ChatDtos.Grounding(
                                "E2", "KB-AIR-003",
                                """
                                2 Cabin Baggage Policy
                                Economy | 1 piece | 7 kg | 55 x 35 x 25 cm
                                Premium Economy | 1 piece | 10 kg | 55 x 35 x 25 cm
                                """)));

        ChatDtos.ChatResult result = gateway.composeGroundedFallback(request);

        assertThat(result.text())
                .contains("Economy - Value - 15 kg [E1]")
                .contains("Economy - 1 piece - 7 kg", "[E2]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void checkInAnswerCoversBothTimingAndIdentificationEvidence() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: When does check-in open, and what identification "
                        + "do I need for a domestic flight?",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-002",
                                """
                                2.1 Check-In Opening and Closing Times
                                Domestic (all sectors) | 48 hours before departure
                                Web and mobile check-in close 1 hour before departure.
                                Counter check-in closes 45 minutes before departure.
                                """),
                        new ChatDtos.Grounding(
                                "E2", "KB-AIR-002",
                                """
                                3.1 Domestic Travel Documentation Requirements
                                All domestic Passengers aged 12 and above must carry at least
                                one government-issued photo ID to board.
                                Indian passports are also accepted for domestic travel.
                                """)));

        ChatDtos.ChatResult result = gateway.composeGroundedFallback(request);

        assertThat(result.text())
                .contains("48 hours before departure", "[E1]")
                .contains("government-issued photo ID", "[E2]");
        assertThat(new CitationAttacher().uncitedFactualSentences(result.text()))
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void domesticAndInternationalTravelDocumentFallbackIsFullyCited() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: Which travel documents are needed for domestic "
                        + "and international journeys?",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-002",
                                """
                                3.1 Domestic Travel (Within India)
                                Aadhaar Card (physical or DigiLocker) | Yes |
                                Must display photo, name, and DOB
                                Indian Passport | Yes | Valid or expired within 5 years
                                Government Employee ID | Yes | With photo
                                """),
                        new ChatDtos.Grounding(
                                "E2", "KB-AIR-002",
                                """
                                3.2 International Travel - Document Requirements
                                Valid Passport | Mandatory for all international routes |
                                Minimum 6 months validity beyond travel date
                                Visa | As required by destination country |
                                Always verify via the airline's TIMATIC integration
                                Return/Onward Ticket | Required by most countries at entry |
                                Carry a printed or digital copy
                                """)));

        ChatDtos.ChatResult result = gateway.composeGroundedFallback(request);

        assertThat(result.text()).contains(
                "Domestic and international travel documents",
                "Aadhaar", "Valid Passport", "[E1]", "[E2]");
        assertThat(new CitationAttacher().uncitedFactualSentences(result.text()))
                .as(result.text())
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void abbreviatedIdRequirementStillIncludesIdentificationEvidence() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: For domestic web check-in, give the closing time "
                        + "and the ID requirement.",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-002",
                                """
                                Domestic web check-in closes 1 hour before departure.
                                Kiosk check-in closes 45 minutes before departure.
                                Counter check-in closes 45 minutes before departure.
                                Baggage drop closes 45 minutes before departure.
                                Check-in closure times are strictly enforced.
                                """),
                        new ChatDtos.Grounding(
                                "E2", "KB-AIR-002",
                                "All domestic Passengers aged 12 and above must carry "
                                        + "a government-issued photo ID to board.")));

        assertThat(gateway.composeGroundedFallback(request).text())
                .contains("1 hour before departure", "[E1]")
                .contains("government-issued photo ID", "[E2]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void compoundCabinAndHoldPetQuestionCoversBothServices() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: Can my cat ride in the cabin and can a large dog "
                        + "travel in the hold?",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-006",
                                """
                                3.4 Pet in Cabin (PETC) Policy
                                Eligible animals | Domestic cats and dogs only
                                Maximum in-cabin weight | Pet plus carrier combined maximum 7 kg
                                Carrier dimensions | Maximum 45 cm x 35 cm x 20 cm
                                Maximum per aircraft | 5 PETC bookings; first-come-first-served
                                Routes | Domestic only; PETC not accepted on international routes (use AVIH for international)
                                Advance booking | Mandatory
                                """)));

        assertThat(gateway.composeGroundedFallback(request).text())
                .contains("PETC", "[E1]")
                .contains("AVIH");
    }

    @SuppressWarnings("unchecked")
    @Test
    void genericWheelchairSsrQuestionIncludesTheRampCode() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: I need wheelchair help from check-in to the aircraft. "
                        + "Which SSR code and request deadline apply?",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-006",
                                "SSR Code | Type | Description | Advance Request"),
                        new ChatDtos.Grounding(
                                "E2", "KB-AIR-006",
                                "Upon arrival, wheelchair assistance is provided from the "
                                        + "aircraft seat to the arrivals hall."),
                        new ChatDtos.Grounding(
                                "E3", "KB-AIR-006",
                                "WCHC | Wheelchair (Cabin) | Passenger is completely immobile; "
                                        + "requires full assistance to/from aircraft seat | 48 hours"),
                        new ChatDtos.Grounding(
                                "E4", "KB-AIR-006",
                                "WCHW | Wheelchair (Steps) | Passenger cannot climb stairs | "
                                        + "48 hours"),
                        new ChatDtos.Grounding(
                                "E5", "KB-AIR-006",
                                "WCHR | Wheelchair (Ramp) | Passenger can manage stairs alone "
                                        + "but needs wheelchair for long distances | 48 hours")));

        assertThat(gateway.composeGroundedFallback(request).text())
                .contains("WCHR", "Wheelchair (Ramp)", "48 hours", "[E1]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void offlineFallbackNormalisesBrokenDashMarkersBeforeCitationValidation() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: Explain the booking workflow from search to payment.",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-001",
                        """
                        3 BOOKING WORKFLOW
                        Search flights ??? enter origin, destination, date, cabin and passenger count.
                        Select flight ??? choose the preferred flight and fare from the results.
                        Payment ??? complete via the airline website, app or authorised call centre.
                        """)));

        ChatDtos.ChatResult result = gateway.composeGroundedFallback(request);

        assertThat(result.text())
                .contains("Search flights — enter", "[E1]")
                .doesNotContain("???");
    }

    @SuppressWarnings("unchecked")
    @Test
    void domesticSeatFallbackPreservesAllFivePublishedCategories() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: What seat types and fares are available on a domestic flight?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-005",
                        """
                        Standard Economy | Regular economy seat | Included
                        Preferred Economy | Front economy rows | INR 400-800
                        Comfort | Extra legroom | INR 900-1500
                        Business Window | Window seat | Included
                        Business Aisle | Aisle seat | Included
                        International exit row | USD 20
                        """)));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer).contains(
                "Standard Economy",
                "Preferred Economy",
                "Comfort",
                "Business Window",
                "Business Aisle");
        assertThat(answer).doesNotContain("International exit row", "USD 20");
    }

    @SuppressWarnings("unchecked")
    @Test
    void multiSpecialServiceFallbackCoversEveryRequestedServiceFamily() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: Compare wheelchair, unaccompanied minor, PETC, AVIH and MEDA assistance.",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-006",
                        """
                        WCHR | Wheelchair ramp assistance | Request 48 hours before departure
                        Unaccompanied Minor | Escort and guardian documents are required
                        PETC | Domestic pet in cabin | Advance booking required
                        AVIH | Pet carried in the aircraft hold | Documents required
                        MEDA | Medical clearance using the MEDIF form | Advance approval required
                        """)));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer).contains("WCHR", "Unaccompanied Minor", "PETC", "AVIH", "MEDA");
    }

    @SuppressWarnings("unchecked")
    @Test
    void unaccompaniedMinorFallbackNamesServiceAndPreservesItsOperationalRequirements() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: How does the unaccompanied-minor service work?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-006",
                        """
                        3.2 Unaccompanied Minor (UM) Service
                        Eligible age range | 5-11 years | Mandatory service
                        Documentation required | Completed UM form and guardian identification
                        Booking method | Contact the UnitedAir call centre before departure
                        """)));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer)
                .contains(
                        "Unaccompanied minor service",
                        "Eligible age range",
                        "Documentation required",
                        "Booking method",
                        "[E1]")
                .doesNotContain("No matching policy");
    }

    @SuppressWarnings("unchecked")
    @Test
    void batteryFallbackHandlesTypoAndStatesTheBaggageRestrictionFromEvidence() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: lithum battery in checked bag ok?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-003",
                        """
                        Restricted and Prohibited Items
                        Spare lithium batteries and power banks | Cabin baggage only
                        Lithium batteries in checked baggage | Not permitted
                        """)));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer)
                .contains("Battery and baggage restrictions", "Lithium batteries", "Not permitted")
                .contains("[E1]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void wheelchairCodeFallbackKeepsTheCodeAndAdvanceRequestDeadline() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: I need wheelchair help from check-in to the aircraft. "
                        + "Which SSR code and request deadline apply?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-006",
                        """
                        Wheelchair Assistance Codes and Procedures
                        WCHR | Wheelchair (Ramp) | Passenger can manage stairs alone but needs
                        wheelchair for long distances | 48 hours
                        """)));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer)
                .contains("Wheelchair special service", "WCHR", "48 hours", "[E1]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void domesticSeatFallbackAlsoIncludesRequestedExitRowEligibility() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: What seat types are available, what do they cost domestically, "
                        + "and who may use an exit row?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-005",
                        """
                        Standard Economy | Regular economy seat | Included
                        Preferred Economy | Front economy rows | INR 400-800
                        Comfort | Extra legroom | INR 800-1,500
                        Business Window | Window seat | Included
                        Business Aisle | Aisle seat | Included
                        Passengers seated in exit rows must be at least 15 years old and able to assist.
                        """)));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer).contains("Preferred Economy", "exit row", "15 years");
    }

    @SuppressWarnings("unchecked")
    @Test
    void generalSeatFallbackPreservesEveryPublishedSeatCategory() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: What seat types, selection fees and upgrade paths are available?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-005 3.1 Seat Categories and Fees",
                        """
                        Standard Economy | Regular economy seat | Included
                        Preferred Economy | Front economy rows | INR 400-800
                        Comfort | Extra legroom | INR 800-1,500
                        Business Window | Window seat | Included
                        Business Aisle | Aisle seat | Included
                        """)));

        assertThat(gateway.composeGroundedFallback(request).text())
                .contains(
                        "Standard Economy",
                        "Preferred Economy",
                        "Comfort",
                        "Business Window",
                        "Business Aisle");
    }

    @SuppressWarnings("unchecked")
    @Test
    void explicitFallbackReasonAndGenerationSourceArePreserved() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(), "QUESTION: What is the baggage allowance?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-003", "Economy cabin baggage is 7 kg.")));

        ChatDtos.ChatResult result =
                gateway.composeGroundedFallback(request, "MODEL_CAPACITY");

        assertThat(result.generationSource())
                .isEqualTo(ChatDtos.GenerationSource.GROUNDED_EXTRACTIVE);
        assertThat(result.degradedReason()).isEqualTo("MODEL_CAPACITY");
    }

    @SuppressWarnings("unchecked")
    @Test
    void groundedFallbackNeverCopiesStructuredToolJsonIntoTheAnswer() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: What is my checked baggage allowance?",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-003",
                                "Economy Value checked baggage allowance is 15 kg."),
                        new ChatDtos.Grounding(
                                "T1", "BookingManagementTool",
                                """
                                {"pnr":"H3PL8M","flightNo":"UA404",
                                "checkedBaggageKg":25,"status":"CONFIRMED"}
                                """)));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer)
                .contains("Economy Value checked baggage allowance is 15 kg")
                .doesNotContain("{", "}", "\"pnr\"", "\"checkedBaggageKg\"");
    }

    @SuppressWarnings("unchecked")
    @Test
    void hostedHttp429IsNormalizedWithoutLeakingProviderOrCredentialAdvice() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        ChatModel model = mock(ChatModel.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(provider.getObject()).thenReturn(model);
        when(model.call(any(Prompt.class))).thenThrow(
                new RuntimeException(
                        "HTTP 429 quota exhausted; replace token secret abc123"));
        ChatGateway gateway = new ChatGateway(
                AiMode.LIVE, provider, new HostedCallLimiter(1));

        ChatDtos.ChatResult result = gateway.completeDirect(
                "Return JSON only.", List.of(), "route this", "");

        assertThat(result.degradedReason()).isEqualTo("MODEL_CAPACITY");
        assertThat(result.text())
                .doesNotContain("429", "quota", "token", "abc123");
    }

    @SuppressWarnings("unchecked")
    @Test
    void groundedFallbackCombinesCheckInStatusAndDomesticIdentificationEvidence() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: For my booking, tell me whether I can check in, "
                        + "what identification I need for this domestic journey, "
                        + "and the current flight status, terminal and gate.",
                List.of(
                        new ChatDtos.Grounding(
                                "T1", "CheckInStatusTool",
                                """
                                eligible: false
                                reason: Check-in opens 48 hours before departure.
                                windowOpensAt: 2026-08-02T00:45:00Z
                                availableChannels: WEB, MOBILE, KIOSK, COUNTER
                                """),
                        new ChatDtos.Grounding(
                                "T2", "CheckInStatusTool",
                                """
                                flightNo: UA101
                                status: ON_TIME
                                terminal: T1
                                gate: A2
                                """),
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-002 domestic identification",
                                """
                                Domestic identification
                                Aadhaar Card (physical or DigiLocker) | Yes
                                Voter ID Card | Yes
                                Indian Passport | Yes
                                Driving Licence | Yes
                                PAN Card | No | Not accepted as a sole travel document
                                """)));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer)
                .contains("48 hours", "[T1]")
                .contains("ON_TIME", "terminal", "T1", "gate", "A2", "[T2]")
                .contains("Aadhaar", "[E1]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void multipartBaggageFallbackCoversEveryNamedPart() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: Give cabin and checked baggage limits, excess rules and "
                        + "power-bank guidance for domestic Economy.",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "Cabin baggage",
                                "Economy | 1 piece | 7 kg | 55 x 35 x 25 cm"),
                        new ChatDtos.Grounding(
                                "E2", "Checked baggage",
                                "Economy | Value | 15 kg"),
                        new ChatDtos.Grounding(
                                "E3", "Excess baggage",
                                "Weight over free allowance | Additional baggage charges may apply"),
                        new ChatDtos.Grounding(
                                "E4", "Restricted items",
                                "High-capacity spare lithium batteries | "
                                        + "Items above airline-permitted battery limits")));

        ChatDtos.ChatResult result = gateway.composeGroundedFallback(request);

        assertThat(result.text())
                .contains("7 kg", "15 kg", "free allowance", "lithium batteries")
                .contains("[E1]", "[E2]", "[E3]", "[E4]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void focusedGroundingCanContributeARequestedFactWithoutLiteralQuestionOverlap() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: What are the checked and cabin baggage limits "
                        + "and restricted-item rules?",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-003 2.1 Cabin Baggage Allowance",
                                "Economy | 1 piece | 7 kg"),
                        new ChatDtos.Grounding(
                                "E2", "KB-AIR-003 3.1 Free Checked Baggage Allowance",
                                "Travel Class | Fare Type | Allowance\n"
                                        + "Economy | Value | 15 kg"),
                        new ChatDtos.Grounding(
                                "E3", "KB-AIR-003 4.1 Items Not Allowed",
                                "Explosive materials | Fireworks and blasting caps")));

        assertThat(gateway.composeGroundedFallback(request).text())
                .contains("7 kg", "15 kg", "Explosive materials")
                .contains("[E1]", "[E2]", "[E3]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void multipartFallbackPreservesFactsFromEveryGroundingBlock() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: Explain frequent-flyer accrual, tiers and redemption.",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-006 4.1 Tier Structure and Qualification",
                                "Silver | 20,000 Tier Points\nGold | 40,000 Tier Points"),
                        new ChatDtos.Grounding(
                                "E2", "KB-AIR-006 4.2 Points Accrual Rates by Fare Class",
                                "J | Business | 200% of distance flown\n"
                                        + "M | Economy | 100% of distance flown"),
                        new ChatDtos.Grounding(
                                "E3", "KB-AIR-006 4.4 Points Redemption Options",
                                "Award flight | 5,000-15,000 points\n"
                                        + "Cabin upgrade | 10,000-20,000 points")));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer)
                .contains("Tier Structure", "Points Accrual", "Points Redemption")
                .contains("[E1]", "[E2]", "[E3]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void singleGroundingIncludesItsPolicySectionForRequestedComplianceContext() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: What are the DGCA CAR-7 flight duty time limitations?",
                List.of(new ChatDtos.Grounding(
                        "E1", "KB-AIR-007 8.1 DGCA CAR-7 FTL Limits",
                        "Maximum flight time per day | 8 hours\n"
                                + "Maximum flight time in 7 days | 40 hours")));

        assertThat(gateway.composeGroundedFallback(request).text())
                .contains("DGCA CAR-7 FTL Limits", "8 hours", "[E1]");
    }

    @SuppressWarnings("unchecked")
    @Test
    void multipartOperationalFallbackKeepsSubstantiveRowsNotOnlySectionLabels() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: Explain the boarding override procedure, gate-change SLA "
                        + "and late-passenger rule.",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "KB-AIR-007 2.1 Boarding Override Procedures",
                                """
                                Override Type | Trigger | Authorised By | Documentation
                                Late Passenger boarding | Gate closed | Duty Manager | ZA-OPS-010
                                """),
                        new ChatDtos.Grounding(
                                "E2", "KB-AIR-007 2.2 Gate Change Notification SLAs",
                                """
                                Notification Timing | Required Action
                                More than 30 minutes | Update FIDS and app
                                15-30 minutes | Make announcements every 5 minutes
                                Less than 15 minutes | Staff escort | Complete within 5 minutes
                                """),
                        new ChatDtos.Grounding(
                                "E3", "KB-AIR-007 2.3 Late Passenger Handling Protocol",
                                """
                                15-20 minutes | Board immediately
                                10-14 minutes | Duty Manager decides and bags may be offloaded
                                """)));

        assertThat(gateway.composeGroundedFallback(request).text())
                .contains(
                        "Duty Manager",
                        "ZA-OPS-010",
                        "FIDS",
                        "Staff escort",
                        "5 minutes",
                        "10-14 minutes",
                        "bags may be offloaded");
    }

    @SuppressWarnings("unchecked")
    @Test
    void groundedFallbackGivesDirectCitedExplosiveAndSharpObjectGuidance() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ChatGateway gateway = new ChatGateway(
                AiMode.OFFLINE, provider, new HostedCallLimiter(1));
        ChatDtos.ChatRequest request = new ChatDtos.ChatRequest(
                "system", List.of(),
                "QUESTION: can i bring weapons and knife to the flight?",
                List.of(
                        new ChatDtos.Grounding(
                                "E1", "Items Not Allowed",
                                "Explosive materials | Fireworks, flares, blasting caps"),
                        new ChatDtos.Grounding(
                                "E2", "Checked Baggage Only",
                                "Sharp objects | Must be safely packed in checked baggage"),
                        new ChatDtos.Grounding(
                                "E3", "Checked Baggage Only",
                                "Firearms/ammunition | Only with required approvals")));

        String answer = gateway.composeGroundedFallback(request).text();

        assertThat(answer)
                .startsWith("Do not bring explosive materials to the airport or flight. "
                        + "Do not carry knives or other sharp objects in the cabin.")
                .contains("Explosive materials", "[E1]")
                .contains("Sharp objects", "checked baggage", "[E2]")
                .contains("Firearms/ammunition", "required approvals", "[E3]");
    }
}
