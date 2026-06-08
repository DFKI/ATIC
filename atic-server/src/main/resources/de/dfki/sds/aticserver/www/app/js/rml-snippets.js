
const rmlSnippets = [

    {
        name: "Triples Map",
        code: `
<#TriplesMap>
    rml:logicalSource [ ] ;
    rml:subjectMap [ ] ;
    rml:predicateObjectMap [ ] .
        `
    },

    {
        name: "CSV Logical Source",
        code: `
rml:logicalSource [
    a rml:LogicalSource ;
    rml:referenceFormulation rml:CSV ;
    rml:source [
        a rml:FilePath ;
        rml:path "/path/to/file.csv"
    ]
] ;
        `
    },

    {
        name: "JSON Logical Source",
        code: `
rml:logicalSource [
    a rml:LogicalSource ;
    rml:referenceFormulation rml:JSONPath ;
    rml:source [
        a rml:FilePath ;
        rml:path "/path/to/file.json"
    ] ;
    rml:iterator "$[*]"
] ;
        `
    },

    {
        name: "XML Logical Source",
        code: `
rml:logicalSource [
    a rml:LogicalSource ;
    rml:referenceFormulation rml:XPath ;
    rml:source [
        a rml:FilePath ;
        rml:path "/path/to/file.xml"
    ] ;
    rml:iterator "/root/item"
] ;
        `
    },

    {
        name: "Subject Map",
        code: `
rml:subjectMap [
    rml:template "urn:resource/{id}" ;
] ;
        `
    },

    {
        name: "Subject Map with Class",
        code: `
rml:subjectMap [
    rml:template "urn:person/{id}" ;
    rml:class ex:Person
] ;
        `
    },

    {
        name: "Blank Node Subject",
        code: `
rml:subjectMap [
    rml:template "person-{id}" ;
    rml:termType rml:BlankNode
] ;
        `
    },

    {
        name: "Predicate Object Map",
        code: `
rml:predicateObjectMap [
    rml:predicate ex:name ;
    rml:objectMap [
        rml:reference "name"
    ]
] ;
        `
    },

    {
        name: "Constant Predicate",
        code: `
rml:predicate ex:name ;
        `
    },

    {
        name: "Constant Object",
        code: `
rml:object "constant value" ;
        `
    },

    {
        name: "Reference Object Map",
        code: `
rml:objectMap [
    rml:reference "columnName"
] ;
        `
    },

    {
        name: "Template Object Map",
        code: `
rml:objectMap [
    rml:template "https://example.com/resource/{id}" ;
    rml:termType rml:IRI
] ;
        `
    },

    {
        name: "Constant Object Map",
        code: `
rml:objectMap [
    rml:constant ex:Value
] ;
        `
    },

    {
        name: "IRI Object",
        code: `
rml:objectMap [
    rml:reference "uri" ;
    rml:termType rml:IRI
] ;
        `
    },

    {
        name: "Blank Node Object",
        code: `
rml:objectMap [
    rml:template "address-{id}" ;
    rml:termType rml:BlankNode
] ;
        `
    },

    {
        name: "Language Tagged Literal",
        code: `
rml:objectMap [
    rml:reference "label" ;
    rml:language "en"
] ;
        `
    },

    {
        name: "Dynamic Language Tag",
        code: `
rml:objectMap [
    rml:reference "label" ;
    rml:languageMap [
        rml:reference "lang"
    ]
] ;
        `
    },

    {
        name: "Datatype Literal",
        code: `
rml:objectMap [
    rml:reference "age" ;
    rml:datatype xsd:integer
] ;
        `
    },

    {
        name: "Dynamic Datatype",
        code: `
rml:objectMap [
    rml:reference "value" ;
    rml:datatypeMap [
        rml:reference "datatype"
    ]
] ;
        `
    },

    {
        name: "Predicate Map",
        code: `
rml:predicateMap [
    rml:template "https://example.com/property/{property}"
] ;
        `
    },

    {
        name: "Graph Map",
        code: `
rml:graphMap [
    rml:template "https://example.com/graph/{dataset}"
] ;
        `
    },

    {
        name: "Constant Graph",
        code: `
rml:graph ex:Graph ;
        `
    },

    {
        name: "Referencing Object Map",
        code: `
rml:objectMap [
    rml:parentTriplesMap <#ParentTriplesMap> ;
    rml:joinCondition [
        rml:childMap [
            rml:reference "parent_id"
        ] ;
        rml:parentMap [
            rml:reference "id"
        ]
    ]
] ;
        `
    },

    {
        name: "Join Condition",
        code: `
rml:joinCondition [
    rml:childMap [
        rml:reference "childId"
    ] ;
    rml:parentMap [
        rml:reference "parentId"
    ]
]
        `
    },

    {
        name: "rdf:type Predicate Object",
        code: `
rml:predicateObjectMap [
    rml:predicate rdf:type ;
    rml:object ex:Person
] ;
        `
    },

    {
        name: "Constant Subject Shortcut",
        code: `
rml:subject ex:Resource ;
        `
    },

    {
        name: "Reference Expression",
        code: `
rml:reference "columnName"
        `
    },

    {
        name: "Template Expression",
        code: `
rml:template "https://example.com/resource/{id}"
        `
    },

    {
        name: "Constant Expression",
        code: `
rml:constant "value"
        `
    }

];