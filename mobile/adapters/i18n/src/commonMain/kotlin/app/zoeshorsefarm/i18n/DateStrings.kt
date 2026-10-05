package app.zoeshorsefarm.i18n

// Long calendar dates ("5. März 2026" / "5 March 2026"). Native only: the web app lets the browser
// format dates (`toLocaleDateString`), the phones have no such call in common code.
val DATE_STRINGS: StringArea =
    StringArea(
        de =
            texts(
                "date.long" to "{day}. {month} {year}",
                "date.month.1" to "Januar",
                "date.month.2" to "Februar",
                "date.month.3" to "März",
                "date.month.4" to "April",
                "date.month.5" to "Mai",
                "date.month.6" to "Juni",
                "date.month.7" to "Juli",
                "date.month.8" to "August",
                "date.month.9" to "September",
                "date.month.10" to "Oktober",
                "date.month.11" to "November",
                "date.month.12" to "Dezember",
            ),
        en =
            texts(
                "date.long" to "{day} {month} {year}",
                "date.month.1" to "January",
                "date.month.2" to "February",
                "date.month.3" to "March",
                "date.month.4" to "April",
                "date.month.5" to "May",
                "date.month.6" to "June",
                "date.month.7" to "July",
                "date.month.8" to "August",
                "date.month.9" to "September",
                "date.month.10" to "October",
                "date.month.11" to "November",
                "date.month.12" to "December",
            ),
    )
