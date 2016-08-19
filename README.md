LTS Vitro Authorities Pilot
========

This is an experimental project in the Cornell Library Technical Services Department. It hopes to address the issues around managing local and external cultural heritage institution authorities in a complex library technology, repository, and data ecosystem.

Part of this management effort includes the use of [Vitro](https://github.com/vivo-project/Vitro/tree/maint-rel-1.8/), the technology behind the [VIVO Project](https://github.com/vivo-project/), to work with a to be determined aggregated cultural heritage institution authorities and external data entities data model. This work is based on using RDF for integration, linking, expansion and querying of this aggregated and enhanced authorities system. The datasets reviewed here are:

- Library of Congress Name Authority File
- Library of Congress Subject Headings
- OCLC Faceted Application of Subject Terminology
- OCLC Virtual International Authority File
- Wikidata
- Local authorities created originally in an Artstor SharedShelf instance
- Geonames
- Getty Art and Authorities Thesaurus
- Getty United List of Artist Names
- Getty Thesaurus of Geographic Names
- Possibly the local Cornell VIVO instance

The data types/models reviewed are:

- Agents
- Locations / Places
- Events
- Concepts / Subjects
- Temporal
- Others as encountered

The Vitro instance would provide a centralized node in our library techno-data ecosystem for management of this data.

More to be written about the cultural heritage institution authorities and external data assessment performed as part of this work, as well as the goals and outcomes of this Vitro project, here.

Project Goals
-------------

- Assess various authorities (local and external) used for data profiles, conversions, serializations, models and points of overlap
- Set up an instance of Vitro for creating authorities
- Merge authorities already created
- Explore the extent to which we could use this stack and model to create and maintain authorities ourselves for multiple repositories
- Find a way to support management of context class entities, URIs, authorities, and graphs between the following repository systems (at least):
    - Fedora 4 instance feeding into Hydra / Curation Concerns
    - MARC dataset managed in a Voyager ILS
    - SharedShelf instance
    - Linked Data for Production Project Native RDF Cataloging Client

Installation
------------

To be written up, based on the Vitro multi-tier recommendations in the VIVO Project documentation, and expanded as part of the LD4L Labs Vitro instance for native RDF library cataloging instance.

Contribute
----------

- Issue Tracker & Discussion Points: github.com/cul-it/lts-vitro-pilot/issues
- Source Code: github.com/cul-it/lts-vitro-pilot
- Further Documentation on this Project + Authorities Assessment: github.com/cul-it/lts-vitro-pilot/wiki

Contact
-------

Get in touch with Christina: cmh329 at cornell dot edu, @cm_harlow on Twitter, @cmh329 on GH, usually on the Code4Lib slack.
