export interface FormDataType {
    type: string | array<string>;
    name: string;
    configuration: {
        location: string;
        fileId?: string;
        fileName?: string;
    };
    description: string;
}
