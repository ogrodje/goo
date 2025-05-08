## goo the Ogrodje Operating System

[![Ogrodje Site Build](https://github.com/ogrodje/goo/actions/workflows/build.yml/badge.svg)](https://github.com/ogrodje/goo/actions/workflows/build.yml)
[![OpenAPI Documentation](https://img.shields.io/badge/OpenAPI_Documentation-📕-blue)](https://goo.ogrodje.si/docs/openapi)

Service is live at [`goo.ogrodje.si`](https://goo.ogrodje.si).


```bash
sbt run
```

## TODO

- [x] Add Eventbrite parser
- [x] Add Meetup.com parser
- [x] Add [Tehnološki Park Ljubljana](https://www.tp-lj.si)
- [x] Add [Primorski Tehnološki Park](https://www.primorski-tp.si/)
- [x] Add [GZS ZIT Dogodki](https://www.gzs.si/zdruzenje_za_informatiko_in_telekomunikacije/vsebina/Dogodki)
- [x] Add [START:UP Slovenija](https://www.startup.si/sl-si/dogodki)
- [x] Integrate the system into [Ogrodje.si](https://ogrodje.si)
- [x] Add [Finance IKTInformator](https://www.finance.si/ikt)
- [x] Improve Sentry integration for better observability
- [x] Add Sentry for error and release tracking
- [ ] Add [FRI Dogodki](https://www.fri.uni-lj.si/sl/koledar-dogodkov)
- [ ] Add [FERI Dogodki](https://feri.um.si/dogodki/)
- [ ] Add [Računalniški Muzej](https://www.racunalniski-muzej.si/)
- [ ] Add [Kompot](https://kompot.si/)
- [ ] Sent automatic newsletter for weekly/monthly events (via [Postmark](https://postmarkapp.com/))
- [ ] Add [GDG Ljubljana](https://gdg.community.dev/gdg-ljubljana/)
- [ ] Add [SAŠA Inkubator](https://sasainkubator.si/dogodki/)
- [ ] Add [Kovačnica](https://kovacnica.si/dogodki/)
- [ ] Add [Podjetniški inkubator Perspektiva](https://www.inkubator-perspektiva.si/)
- [ ] Add [Inkubator Savinjske regije](https://inkubatorsr.si/)
- [ ] Add [LUI (Ljubljanski univerzitetni inkubator)](https://lui.si/aktualno/)
- [ ] Allow manual adding of events
- [ ] Send batch emails via
  Postmark ([API documentation](https://postmarkapp.com/developer/user-guide/send-email-with-api/batch-emails))
- [ ] Allow users to be notified about new events
- [ ] Expose the data via RSS feed
- [ ] Expose the data via iCal format/feed
- [ ] Add parser for Facebook Events
- [ ] Add parser for LinkedIn Events
