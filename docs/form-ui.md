# Form page / 字段列表页
Evidence opened before designing `GET /forms`. These are the pages that were read, not a copied designer.
- Form.io stores each form as Form JSON. The builder writes a `components` list; each component has a `key`, a `type`, and `validate.required`. The same JSON is what the renderer reads. https://help.form.io/form-building/form-json
- JSON Forms renders a form from JSON Schema. The data schema lists properties and which of them are required. A second UI schema decides order, layout, and whether a control is shown. https://jsonforms.io/docs/
- Access says the Field List pane may not be displayed. Open the form in Design view, then click Add Existing Fields or press ALT+F8 before the fields are visible. https://support.microsoft.com/en-us/access/add-a-field-to-a-form-or-report
- The miss this page is built against: the designer is open, Add Existing Fields does nothing, and the field list pane never comes up, so the person cannot see the fields they still need. https://learn.microsoft.com/en-us/answers/questions/5268232/form-design-in-access-will-not-display-field-list
`GET /forms` is the opposite of that complaint. One sentence says this is the field list, not a designer. Each row is a field name, its type, and whether it is required, Chinese first and English second. The page reads `endpoint-publication.form.yaml` through `FormRenderer`. There is no drag-and-drop designer and no form database.
`GET /forms` 对着的就是这个抱怨。一句话说明这是字段列表，不是设计器。每一行是字段名、类型，以及是否必填，中文在前、英文在后。页面经 `FormRenderer` 读 `endpoint-publication.form.yaml`。没有拖拽设计器，也没有表单库。
